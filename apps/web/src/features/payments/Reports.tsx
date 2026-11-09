import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { SessionClient, ApiError } from '../../api/session'
import {
  audit,
  dashboard,
  date,
  money,
  page,
  paymentStatus,
  type summary,
} from './api'
import type { z } from 'zod'

type Props = { client: SessionClient; merchantId: string }
export function Failure({
  error,
  retry,
  label,
}: {
  error: unknown
  retry: () => void
  label: string
}) {
  return (
    <div className="report-error">
      <p role="alert">
        {error instanceof ApiError
          ? error.message
          : 'Could not load this information. Please try again.'}
      </p>
      <button className="check-button" onClick={retry}>
        Retry {label}
      </button>
    </div>
  )
}
function day(offset = 0) {
  return new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10)
}
function range(params: URLSearchParams) {
  const from = params.get('from') ?? day(-29)
  const to = params.get('to') ?? day()
  const a = Date.parse(from + 'T00:00:00Z')
  const b = Date.parse(to + 'T00:00:00Z') + 86400000
  const valid =
    /^\d{4}-\d{2}-\d{2}$/.test(from) &&
    /^\d{4}-\d{2}-\d{2}$/.test(to) &&
    Number.isFinite(a) &&
    Number.isFinite(b) &&
    a >= 0 &&
    b > a &&
    b - a <= 366 * 86400000 &&
    new Date(a).toISOString().startsWith(from) &&
    new Date(b - 86400000).toISOString().startsWith(to) &&
    b < Date.parse('9999-12-31T00:00:00Z')
  return {
    from,
    to,
    valid,
    query: valid
      ? new URLSearchParams({
          from: new Date(a).toISOString(),
          to: new Date(b).toISOString(),
        })
      : new URLSearchParams(),
  }
}
function Filters({ children }: { children?: React.ReactNode }) {
  const [params, set] = useSearchParams()
  const dates = range(params)
  function change(key: string, value: string) {
    const next = new URLSearchParams(params)
    next.delete('cursor')
    if (value) next.set(key, value)
    else next.delete(key)
    set(next)
  }
  return (
    <div className="report-filters">
      <div className="period-buttons" aria-label="Reporting period">
        {[1, 7, 30].map((days) => (
          <button
            key={days}
            className="text-button"
            onClick={() => {
              const next = new URLSearchParams(params)
              next.set('from', day(1 - days))
              next.set('to', day())
              next.delete('cursor')
              set(next)
            }}
          >
            {days === 1 ? 'Today' : `${days} days`}
          </button>
        ))}
      </div>
      <label>
        From (UTC)
        <input
          type="date"
          value={dates.from}
          onChange={(e) => change('from', e.target.value)}
        />
      </label>
      <label>
        To (UTC)
        <input
          type="date"
          value={dates.to}
          onChange={(e) => change('to', e.target.value)}
        />
      </label>
      {children}
      {!dates.valid ? (
        <p role="alert">Choose valid dates within a 366-day period.</p>
      ) : null}
    </div>
  )
}
export function PaymentTable({
  items,
  merchantId,
}: {
  items: z.infer<typeof summary>[]
  merchantId: string
}) {
  return items.length ? (
    <div className="table-scroll">
      <table className="report-table">
        <caption>Demo payment activity · times in Melbourne</caption>
        <thead>
          <tr>
            <th scope="col">Receipt / payment</th>
            <th scope="col">Terminal</th>
            <th scope="col">Gross</th>
            <th scope="col">Refunded</th>
            <th scope="col">Net</th>
            <th scope="col">Status</th>
          </tr>
        </thead>
        <tbody>
          {items.map((p) => (
            <tr key={p.paymentId}>
              <td data-label="Receipt / payment">
                <Link
                  className="receipt-link"
                  to={`/app/merchants/${merchantId}/payments/${p.paymentId}`}
                >
                  {p.receiptNumber ?? p.paymentId}
                </Link>
                <small>{date(p.authorisedAt ?? p.createdAt)}</small>
              </td>
              <td data-label="Terminal">{p.terminalLabel}</td>
              <td data-label="Gross">{money(p.amountMinor)}</td>
              <td data-label="Refunded">{money(p.refundedAmountMinor)}</td>
              <td data-label="Net">{money(p.netAmountMinor)}</td>
              <td data-label="Status">
                <span className={`payment-badge ${p.status.toLowerCase()}`}>
                  {p.status}
                </span>
                {p.refundStatus !== 'NONE' ? (
                  <small>{p.refundStatus}</small>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  ) : (
    <p className="terminal-empty">No payments in this period.</p>
  )
}
function Pages({ next }: { next: string | null }) {
  const [params, set] = useSearchParams()
  function go(cursor: string | null) {
    const copy = new URLSearchParams(params)
    if (cursor) copy.set('cursor', cursor)
    else copy.delete('cursor')
    set(copy)
  }
  return (
    <div className="terminal-pages">
      {params.has('cursor') ? (
        <button className="text-button" onClick={() => go(null)}>
          First page
        </button>
      ) : null}
      {next ? (
        <button className="text-button" onClick={() => go(next)}>
          Next page
        </button>
      ) : null}
    </div>
  )
}
export function Payments({ client, merchantId }: Props) {
  const [params, set] = useSearchParams()
  const dates = range(params)
  const query = new URLSearchParams(dates.query)
  query.set('limit', '25')
  for (const name of ['status', 'cursor']) {
    const value = params.get(name)
    if (value) query.set(name, value)
  }
  const payments = useQuery({
    queryKey: ['payments', merchantId, query.toString()],
    queryFn: ({ signal }) =>
      client.get(`/merchants/${merchantId}/payments?${query}`, page, signal),
    enabled: dates.valid,
    retry: false,
  })
  return (
    <section className="report-section">
      <h2>Transactions</h2>
      <Filters>
        <label>
          Payment status
          <select
            value={params.get('status') ?? ''}
            onChange={(e) => {
              const next = new URLSearchParams(params)
              next.delete('cursor')
              if (e.target.value) next.set('status', e.target.value)
              else next.delete('status')
              set(next)
            }}
          >
            <option value="">All statuses</option>
            {paymentStatus.options.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </label>
      </Filters>
      {dates.valid ? (
        payments.isPending ? (
          <p role="status" className="report-loading">
            Loading payments…
          </p>
        ) : payments.isError ? (
          <Failure
            error={payments.error}
            retry={() => void payments.refetch()}
            label="payments"
          />
        ) : (
          <>
            <PaymentTable items={payments.data.items} merchantId={merchantId} />
            <Pages next={payments.data.nextCursor} />
          </>
        )
      ) : null}
    </section>
  )
}
export function Overview({ client, merchantId }: Props) {
  const [params] = useSearchParams()
  const dates = range(params)
  const result = useQuery({
    queryKey: ['dashboard', merchantId, dates.query.toString()],
    queryFn: ({ signal }) =>
      client.get(
        `/merchants/${merchantId}/dashboard?${dates.query}`,
        dashboard,
        signal,
      ),
    enabled: dates.valid,
    retry: false,
  })
  const data = result.data
  const max = Math.max(
    1,
    ...(data?.dailyBuckets.map((d) => d.approvedCount) ?? []),
  )
  return (
    <section className="report-section">
      <div className="section-title">
        <h2>Overview</h2>
        <span>Simulated value, clearly accounted for.</span>
      </div>
      <Filters />
      {dates.valid ? (
        result.isPending ? (
          <p role="status" className="report-loading">
            Loading overview…
          </p>
        ) : result.isError ? (
          <Failure
            error={result.error}
            retry={() => void result.refetch()}
            label="overview"
          />
        ) : data ? (
          <>
            <div className="kpi-grid">
              {[
                ['Approved payments', data.approvedCount.toLocaleString()],
                ['Gross approved', money(data.grossApprovedMinor)],
                ['Refunded', money(data.totalRefundedMinor)],
                ['Net demo revenue', money(data.netApprovedMinor)],
              ].map(([label, value]) => (
                <div className="kpi" key={label}>
                  <span>{label}</span>
                  <strong>{value}</strong>
                </div>
              ))}
            </div>
            <section className="daily-chart">
              <div className="section-title">
                <h3>Daily approved payments</h3>
                <span>{data.declinedCount} declined in this period</span>
              </div>
              <div
                className="chart-bars"
                role="img"
                aria-label="Daily approved transaction count"
              >
                {data.dailyBuckets.map((d) => (
                  <div
                    key={d.date}
                    title={`${d.date}: ${d.approvedCount} approved`}
                    style={{
                      height: `${d.approvedCount === 0 ? 0 : Math.max(2, (d.approvedCount / max) * 100)}%`,
                    }}
                  />
                ))}
              </div>
              <p className="chart-caption">
                {dates.from} — {dates.to} · UTC authorisation dates
              </p>
              <details>
                <summary>View daily data</summary>
                <div className="table-scroll">
                  <table
                    className="report-table"
                    aria-label="Daily approved payments"
                  >
                    <thead>
                      <tr>
                        <th>Date (UTC)</th>
                        <th>Approved count</th>
                        <th>Gross approved</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.dailyBuckets.map((d) => (
                        <tr key={d.date}>
                          <td>{d.date}</td>
                          <td>{d.approvedCount}</td>
                          <td>{money(d.grossApprovedMinor)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </details>
            </section>
            <p className="report-note">
              Totals group payments by authorisation date and subtract all
              associated refunds, including later refunds. No bank transfers or
              settlement.
            </p>
            <div className="section-title">
              <h3>Recent payments</h3>
              <Link
                className="text-button"
                to={`/app/merchants/${merchantId}/payments?${params}`}
              >
                All transactions →
              </Link>
            </div>
            <PaymentTable items={data.recentPayments} merchantId={merchantId} />
          </>
        ) : null
      ) : null}
    </section>
  )
}
export function Audit({
  client,
  merchantId,
  owner,
}: Props & { owner: boolean }) {
  const [params, set] = useSearchParams()
  const dates = range(params)
  const query = new URLSearchParams(dates.query)
  query.set('limit', '25')
  for (const key of ['eventType', 'cursor']) {
    const value = params.get(key)
    if (value) query.set(key, value)
  }
  const result = useQuery({
    queryKey: ['audit', merchantId, query.toString()],
    queryFn: ({ signal }) =>
      client.get(
        `/merchants/${merchantId}/audit-events?${query}`,
        audit,
        signal,
      ),
    enabled: owner && dates.valid,
    retry: false,
  })
  if (!owner)
    return (
      <section>
        <h2>Owner access required</h2>
        <p>This audit log is available to merchant owners.</p>
      </section>
    )
  return (
    <section className="report-section">
      <h2>Audit log</h2>
      <Filters>
        <label>
          Event type
          <input
            value={params.get('eventType') ?? ''}
            maxLength={80}
            placeholder="e.g. refund.created"
            onChange={(e) => {
              const next = new URLSearchParams(params)
              next.delete('cursor')
              if (e.target.value) next.set('eventType', e.target.value)
              else next.delete('eventType')
              set(next)
            }}
          />
        </label>
      </Filters>
      {dates.valid ? (
        result.isPending ? (
          <p role="status">Loading audit events…</p>
        ) : result.isError ? (
          <Failure
            error={result.error}
            retry={() => void result.refetch()}
            label="audit"
          />
        ) : (
          <>
            {result.data.items.length ? (
              <div className="table-scroll">
                <table className="report-table">
                  <caption>
                    Sanitised merchant events · times in Melbourne
                  </caption>
                  <thead>
                    <tr>
                      <th>Time</th>
                      <th>Actor</th>
                      <th>Event</th>
                      <th>Resource</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.data.items.map((e) => (
                      <tr key={e.id}>
                        <td data-label="Time">{date(e.createdAt)}</td>
                        <td data-label="Actor">{e.actorUserId ?? 'System'}</td>
                        <td data-label="Event">{e.eventType}</td>
                        <td data-label="Resource">
                          {e.resourceType}
                          <small>{e.resourceId ?? '—'}</small>
                          <small>Request {e.requestId}</small>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="terminal-empty">No audit events in this period.</p>
            )}
            <Pages next={result.data.nextCursor} />
          </>
        )
      ) : null}
    </section>
  )
}
