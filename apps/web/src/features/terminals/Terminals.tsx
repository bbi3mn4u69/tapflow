import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { ApiError, SessionClient } from '../../api/session'

const terminal = z.object({
  terminalId: z.uuid(),
  merchantId: z.uuid(),
  label: z.string().max(80),
  status: z.enum(['ACTIVE', 'DISABLED']),
  pairedAt: z.iso.datetime({ precision: null }),
  lastSeenAt: z.iso.datetime({ precision: null }).nullable(),
  demo: z.literal(true),
})
const page = z.object({
  items: z.array(terminal).max(100),
  nextCursor: z.string().nullable(),
  demo: z.literal(true),
})
const code = z.object({
  pairingCode: z.string().regex(/^[0-9A-HJKMNP-TV-Z]{13}$/),
  merchantId: z.uuid(),
  expiresAt: z.iso.datetime({ precision: null }),
  demo: z.literal(true),
})
function message(error: unknown) {
  return error instanceof ApiError
    ? error.message
    : 'Could not complete the request. Please try again.'
}

export default function Terminals({
  client,
  merchantId,
  owner,
}: {
  client: SessionClient
  merchantId: string
  owner: boolean
}) {
  const [modal, setModal] = useState(false)
  const [confirm, setConfirm] = useState<z.infer<typeof terminal> | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [cursor, setCursor] = useState<string | null>(null)
  const terminals = useQuery({
    queryKey: ['terminals', merchantId, cursor],
    queryFn: ({ signal }) =>
      client.get(
        `/merchants/${merchantId}/terminals?limit=25${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
        page,
        signal,
      ),
    retry: false,
  })
  async function disable() {
    if (!confirm || busy) return
    setBusy(true)
    setError(null)
    try {
      await client.post(
        `/merchants/${merchantId}/terminals/${confirm.terminalId}/disable`,
        terminal,
        {},
      )
      setConfirm(null)
      await terminals.refetch()
    } catch (error) {
      setError(message(error))
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className="terminal-section">
      <div className="terminal-heading">
        <div>
          <p className="eyebrow">CONNECT YOUR COUNTER</p>
          <h2>Terminals</h2>
        </div>
        {owner ? (
          <button className="check-button" onClick={() => setModal(true)}>
            Create pairing code
          </button>
        ) : (
          <span className="role-badge">Owner manages pairing</span>
        )}
      </div>
      <p>
        Pair the Android POS app with a one-time owner code. No real money
        moves.
      </p>
      {terminals.isPending ? (
        <p role="status">Loading terminals…</p>
      ) : terminals.isError ? (
        <>
          <p role="alert">{message(terminals.error)}</p>
          <button
            className="check-button"
            onClick={() => void terminals.refetch()}
          >
            Retry terminals
          </button>
        </>
      ) : (
        <>
          {terminals.data.items.length === 0 ? (
            <p className="terminal-empty">No terminals paired yet.</p>
          ) : (
            <ul className="terminal-list">
              {terminals.data.items.map((item) => (
                <li key={item.terminalId}>
                  <div>
                    <strong>{item.label}</strong>
                    <span>
                      {item.status === 'ACTIVE' ? 'Active' : 'Disabled'} ·
                      Paired {new Date(item.pairedAt).toLocaleDateString()}
                    </span>
                  </div>
                  {owner && item.status === 'ACTIVE' ? (
                    <button
                      className="text-button"
                      onClick={() => {
                        setError(null)
                        setConfirm(item)
                      }}
                    >
                      Disable {item.label}
                    </button>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
          <div className="terminal-pages">
            {cursor ? (
              <button className="text-button" onClick={() => setCursor(null)}>
                First page
              </button>
            ) : null}
            {terminals.data.nextCursor ? (
              <button
                className="text-button"
                onClick={() => setCursor(terminals.data.nextCursor)}
              >
                Next page
              </button>
            ) : null}
          </div>
        </>
      )}
      {modal ? (
        <CodeModal
          client={client}
          merchantId={merchantId}
          close={() => setModal(false)}
        />
      ) : null}
      {confirm ? (
        <Modal
          close={() => {
            if (!busy) setConfirm(null)
          }}
          title="Disable this terminal?"
          label="Close terminal confirmation"
        >
          <p>
            {confirm.label} will no longer be available for checkout. This does
            not delete its history.
          </p>
          {error ? <p role="alert">{error}</p> : null}
          <button
            className="check-button"
            disabled={busy}
            onClick={() => void disable()}
          >
            {busy ? 'Disabling…' : 'Confirm disable'}
          </button>
        </Modal>
      ) : null}
    </section>
  )
}
function Modal({
  children,
  close,
  title,
  label,
}: {
  children: React.ReactNode
  close: () => void
  title: string
  label: string
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const previous = useRef<HTMLElement | null>(null)
  useEffect(() => {
    if (!previous.current && document.activeElement instanceof HTMLElement)
      previous.current = document.activeElement
    dialog.current?.showModal()
    return () => {
      if (previous.current?.isConnected) previous.current.focus()
    }
  }, [])
  return (
    <dialog
      className="pair-dialog"
      ref={dialog}
      aria-label={title}
      onClose={close}
      onCancel={(event) => {
        event.preventDefault()
        close()
      }}
    >
      <div className="dialog-heading">
        <h2>{title}</h2>
        <button className="text-button" aria-label={label} onClick={close}>
          Close
        </button>
      </div>
      <p className="eyebrow">DEMO / NO REAL MONEY</p>
      {children}
    </dialog>
  )
}
function CodeModal({
  client,
  merchantId,
  close,
}: {
  client: SessionClient
  merchantId: string
  close: () => void
}) {
  const [key] = useState(() => crypto.randomUUID())
  const [result, setResult] = useState<z.infer<typeof code> | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    let active = true
    const controller = new AbortController()
    void client
      .post(
        `/merchants/${merchantId}/terminals/pairing-codes`,
        code,
        {},
        key,
        controller.signal,
      )
      .then((response) => {
        if (response.merchantId !== merchantId)
          throw new Error('Unexpected merchant')
        if (active) {
          setNow(Date.now())
          setResult(response)
          setBusy(false)
        }
      })
      .catch((error) => {
        if (active) {
          setError(message(error))
          setBusy(false)
        }
      })
    return () => {
      active = false
      controller.abort()
    }
  }, [client, merchantId, key, attempt])
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  const seconds = result
    ? Math.max(0, Math.ceil((Date.parse(result.expiresAt) - now) / 1000))
    : null
  return (
    <Modal close={close} title="Pair your POS" label="Close pairing code">
      <p>
        Open TapFlow POS, sign in as a member of this merchant, then enter this
        code. It expires in 10 minutes and can be used once.
      </p>
      {busy ? <p role="status">Creating pairing code…</p> : null}
      {error ? (
        <>
          <p role="alert">{error}</p>
          <button
            className="check-button"
            onClick={() => {
              setBusy(true)
              setError(null)
              setAttempt((a) => a + 1)
            }}
          >
            Retry code request
          </button>
        </>
      ) : null}
      {result ? (
        seconds === 0 ? (
          <p role="status">
            This code expired. Close this dialog to create a new one.
          </p>
        ) : (
          <>
            <p className="pair-code" aria-label="Pairing code">
              {result.pairingCode}
            </p>
            <p role="timer">
              Expires in {Math.floor((seconds ?? 0) / 60)}:
              {String((seconds ?? 0) % 60).padStart(2, '0')}
            </p>
            <p>
              Keep this dialog open until pairing finishes. Closing it hides the
              code.
            </p>
          </>
        )
      ) : null}
    </Modal>
  )
}
