import { useRef, useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { z } from 'zod'
import { ApiError, SessionClient } from '../../api/session'
import Modal from '../../components/Modal'
import { cents, date, detail, money, refundResult } from './api'
import { Failure } from './Reports'
const pendingSchema = z.object({
  key: z.uuid(),
  amountMinor: z.number().int().min(1).max(1000000),
  reason: z.enum(['CUSTOMER_REQUEST', 'DUPLICATE', 'OTHER']),
})
type Pending = z.infer<typeof pendingSchema>
export default function PaymentDetail({
  client,
  merchantId,
  paymentId,
  owner,
}: {
  client: SessionClient
  merchantId: string
  paymentId: string
  owner: boolean
}) {
  // shortcut: recovery is scoped to this tab; add server-side request lookup if cross-device recovery is needed.
  const storageKey = `tapflow-refund:${client.getSnapshot().user?.id}:${merchantId}:${paymentId}`
  const [pending, setPending] = useState<Pending | null>(() => {
    const raw = sessionStorage.getItem(storageKey)
    return raw ? pendingSchema.parse(JSON.parse(raw)) : null
  })
  const [open, setOpen] = useState(() => pending !== null)
  const validReference = z.uuid().safeParse(paymentId).success
  const result = useQuery({
    queryKey: ['payment', merchantId, paymentId],
    queryFn: ({ signal }) =>
      client.get(
        `/merchants/${merchantId}/payments/${encodeURIComponent(paymentId)}`,
        detail,
        signal,
      ),
    enabled: validReference,
    retry: false,
  })
  if (!validReference) return <p role="alert">Invalid payment reference.</p>
  if (result.isPending)
    return (
      <p role="status" className="report-loading">
        Loading payment…
      </p>
    )
  if (result.isError)
    return (
      <Failure
        error={result.error}
        retry={() => void result.refetch()}
        label="payment"
      />
    )
  const p = result.data
  const remaining = p.amountMinor - p.refundedAmountMinor
  return (
    <section className="report-section">
      <Link
        className="text-button"
        to={`/app/merchants/${merchantId}/payments`}
      >
        ← Transactions
      </Link>
      <div className="receipt-panel">
        <div className="section-title">
          <h2>Demo receipt</h2>
          <span className={`payment-badge ${p.status.toLowerCase()}`}>
            {p.status}
          </span>
        </div>
        <p className="eyebrow">DEMO / NO REAL MONEY</p>
        <div className="receipt-amount">{money(p.amountMinor)}</div>
        <p>
          {p.merchantName} · {p.terminalLabel}
        </p>
        <dl className="receipt-fields">
          <dt>Receipt</dt>
          <dd>{p.receiptNumber ?? 'No approval receipt'}</dd>
          <dt>Payment</dt>
          <dd>{p.paymentId}</dd>
          <dt>Card alias</dt>
          <dd>{p.cardAlias ?? '—'}</dd>
          <dt>Created</dt>
          <dd>{date(p.createdAt)} Melbourne</dd>
          <dt>Authorised</dt>
          <dd>{p.authorisedAt ? date(p.authorisedAt) + ' Melbourne' : '—'}</dd>
          <dt>Decision</dt>
          <dd>{p.decisionCode ?? '—'}</dd>
          <dt>Refund status</dt>
          <dd>{p.refundStatus}</dd>
          <dt>Refunded</dt>
          <dd>{money(p.refundedAmountMinor)}</dd>
          <dt>Net demo value</dt>
          <dd>{money(p.netAmountMinor)}</dd>
        </dl>
        <p className="report-note">
          A simulated authorisation receipt. No banking settlement or real money
          transfer.
        </p>
        {owner &&
        (pending !== null || (p.status === 'APPROVED' && remaining > 0)) ? (
          <button className="check-button" onClick={() => setOpen(true)}>
            {pending ? 'Recover refund' : 'Refund'}
          </button>
        ) : null}
      </div>
      <section>
        <h3>Refund history</h3>
        {p.refunds.length ? (
          <ul className="refund-history">
            {p.refunds.map((r) => (
              <li key={r.refundId}>
                <strong>{money(r.amountMinor)}</strong>
                <span>
                  {r.reason.replaceAll('_', ' ')} · {date(r.createdAt)}
                </span>
                <small>{r.refundId}</small>
              </li>
            ))}
          </ul>
        ) : (
          <p>No refunds issued.</p>
        )}
      </section>
      {owner && open ? (
        <RefundDialog
          client={client}
          merchantId={merchantId}
          paymentId={paymentId}
          remaining={remaining}
          pending={pending}
          save={(value) => {
            if (value) sessionStorage.setItem(storageKey, JSON.stringify(value))
            else sessionStorage.removeItem(storageKey)
            setPending(value)
          }}
          close={() => setOpen(false)}
        />
      ) : null}
    </section>
  )
}
function RefundDialog({
  client,
  merchantId,
  paymentId,
  remaining,
  pending,
  save,
  close,
}: {
  client: SessionClient
  merchantId: string
  paymentId: string
  remaining: number
  pending: Pending | null
  save: (value: Pending | null) => void
  close: () => void
}) {
  const cache = useQueryClient()
  const [amount, setAmount] = useState(() =>
    pending
      ? (pending.amountMinor / 100).toFixed(2)
      : (remaining / 100).toFixed(2),
  )
  const [reason, setReason] = useState<Pending['reason']>(
    pending?.reason ?? 'CUSTOMER_REQUEST',
  )
  const [busy, setBusy] = useState(false)
  const inFlight = useRef(false)
  const [error, setError] = useState<string | null>(null)
  const [rejected, setRejected] = useState(false)
  const minor = cents(amount)
  const valid = pending !== null || (minor !== null && minor <= remaining)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (inFlight.current || !valid || rejected) return
    inFlight.current = true
    setBusy(true)
    setError(null)
    const request = pending ?? {
      key: crypto.randomUUID(),
      amountMinor: minor!,
      reason,
    }
    try {
      save(request)
      const response = await client.post(
        `/merchants/${merchantId}/payments/${encodeURIComponent(paymentId)}/refunds`,
        refundResult,
        { amountMinor: request.amountMinor, reason: request.reason },
        request.key,
      )
      if (
        response.paymentId !== paymentId ||
        response.amountMinor !== request.amountMinor
      )
        throw new Error('Unexpected refund response')
      save(null)
      await Promise.all([
        cache.invalidateQueries({
          queryKey: ['payment', merchantId, paymentId],
        }),
        cache.invalidateQueries({ queryKey: ['payments', merchantId] }),
        cache.invalidateQueries({ queryKey: ['dashboard', merchantId] }),
        cache.invalidateQueries({ queryKey: ['audit', merchantId] }),
      ])
      close()
    } catch (failure) {
      if (
        failure instanceof ApiError &&
        !pending &&
        [400, 403, 404, 409].includes(failure.status)
      ) {
        save(null)
        setRejected(true)
        setError(
          'This refund was not accepted. Close this dialog and review the refreshed remaining amount.',
        )
        await cache.invalidateQueries({
          queryKey: ['payment', merchantId, paymentId],
        })
      } else
        setError(
          'The refund outcome is not confirmed. Retry this saved request to recover its result. Do not start another refund.',
        )
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }
  return (
    <Modal
      title="Refund demo payment"
      label="Close refund"
      close={() => {
        if (!busy) close()
      }}
    >
      <form onSubmit={(event) => void submit(event)}>
        <p>
          This credits simulated value to the original demo wallet. No real
          money moves.
        </p>
        <p>
          Remaining refundable amount: <strong>{money(remaining)}</strong>
        </p>
        {pending ? (
          <p role="status">
            A saved refund needs recovery. Its amount, reason and request key
            are preserved across reloads in this tab.
          </p>
        ) : null}
        <label htmlFor="refund-amount">Refund amount in AUD</label>
        <input
          id="refund-amount"
          inputMode="decimal"
          value={amount}
          disabled={busy || pending !== null || rejected}
          onChange={(e) => setAmount(e.target.value)}
          aria-invalid={!valid}
        />
        <label htmlFor="refund-reason">Reason</label>
        <select
          id="refund-reason"
          value={reason}
          disabled={busy || pending !== null || rejected}
          onChange={(e) => setReason(e.target.value as Pending['reason'])}
        >
          <option value="CUSTOMER_REQUEST">Customer request</option>
          <option value="DUPLICATE">Duplicate</option>
          <option value="OTHER">Other</option>
        </select>
        {minor !== null ? (
          <p>
            The original wallet will be credited {money(minor)} in demo value.
          </p>
        ) : null}
        {!valid ? (
          <p role="alert">
            Enter a positive amount up to {money(remaining)}, with at most two
            decimal places.
          </p>
        ) : null}
        {error ? <p role="alert">{error}</p> : null}
        <button
          className="check-button"
          type="submit"
          disabled={busy || !valid || rejected}
        >
          {busy
            ? 'Confirming…'
            : pending
              ? 'Retry saved refund'
              : 'Confirm refund'}
        </button>
      </form>
    </Modal>
  )
}
