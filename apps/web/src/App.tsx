import {
  createContext,
  useContext,
  useEffect,
  useState,
  useSyncExternalStore,
  type FormEvent,
} from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Link,
  Navigate,
  Outlet,
  Route,
  Routes,
  useNavigate,
  useParams,
} from 'react-router'
import { z } from 'zod'
import { ApiError, merchantSchema, SessionClient } from './api/session'

const SessionContext = createContext<SessionClient | null>(null)
function useSession() {
  const client = useContext(SessionContext)
  if (!client) throw new Error('Session provider is missing')
  return {
    client,
    ...useSyncExternalStore(client.subscribe, client.getSnapshot),
  }
}
function Brand() {
  return (
    <Link to="/app" className="brand" aria-label="TapFlow workspace">
      <span className="brand-mark" aria-hidden="true">
        t<span>f</span>
      </span>
      <span>
        tapflow<span className="brand-dot">.</span>
      </span>
    </Link>
  )
}
function DemoBadge() {
  return <span className="demo-badge">DEMO / NO REAL MONEY</span>
}
function Initializing() {
  const session = useSession()
  return (
    <main className="session-message">
      <Brand />
      <DemoBadge />
      {session.status === 'error' ? (
        <>
          <h1>Let’s reconnect.</h1>
          <p role="alert">{session.error}</p>
          <button
            className="check-button"
            onClick={() => void session.client.restore()}
          >
            Try again
          </button>
        </>
      ) : (
        <p role="status">Restoring your session…</p>
      )}
    </main>
  )
}
export default function App() {
  const cache = useQueryClient()
  const [client] = useState(() => new SessionClient(() => cache.clear()))
  useEffect(() => {
    void client.restore()
  }, [client])
  return (
    <SessionContext.Provider value={client}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<Protected />}>
          <Route path="/" element={<Navigate to="/app" replace />} />
          <Route path="/app" element={<Workspace />} />
          <Route
            path="/app/merchants/:merchantId/overview"
            element={<Workspace />}
          />
          <Route path="/403" element={<Workspace denied />} />
        </Route>
        <Route
          path="*"
          element={
            <main className="session-message">
              <Brand />
              <DemoBadge />
              <h1>Page not found</h1>
              <Link to="/app">Return to workspace</Link>
            </main>
          }
        />
      </Routes>
    </SessionContext.Provider>
  )
}
function Protected() {
  const session = useSession()
  if (session.status !== 'ready') return <Initializing />
  return session.user ? <Outlet /> : <Navigate to="/login" replace />
}
const loginSchema = z.object({
  email: z.email().max(254),
  password: z.string().min(1).max(128),
})
function Login() {
  const session = useSession()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [visible, setVisible] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<{
    email?: string
    password?: string
  }>({})
  if (session.status !== 'ready') return <Initializing />
  if (session.user) return <Navigate to="/app" replace />
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const parsed = loginSchema.safeParse({ email: email.trim(), password })
    if (!parsed.success) {
      const fields = parsed.error.flatten().fieldErrors
      setFieldErrors({
        email: fields.email?.length
          ? 'Enter a valid email address.'
          : undefined,
        password: fields.password?.length
          ? 'Enter your password (up to 128 characters).'
          : undefined,
      })
      return
    }
    setBusy(true)
    setError(null)
    setFieldErrors({})
    try {
      await session.client.login(parsed.data.email, parsed.data.password)
    } catch (failure) {
      setError(
        failure instanceof ApiError && failure.status === 401
          ? 'Email or password is incorrect.'
          : failure instanceof ApiError && failure.status === 429
            ? `${failure.message}${failure.retryAfter ? ` Try again in ${Math.ceil(failure.retryAfter / 60)} minute(s).` : ''}`
            : 'We could not connect. Please try again.',
      )
    } finally {
      setBusy(false)
      setPassword('')
    }
  }
  return (
    <div className="login-page">
      <header className="login-topbar">
        <Brand />
        <DemoBadge />
      </header>
      <main className="login-grid" id="main">
        <section className="login-copy" aria-labelledby="login-title">
          <p className="eyebrow">YOUR MERCHANT WORKSPACE</p>
          <h1 id="login-title">Welcome back.</h1>
          <p className="intro">
            One place for your team, terminals and demo payments.
          </p>
          <form
            onSubmit={(event) => void submit(event)}
            noValidate
            aria-busy={busy}
          >
            <label htmlFor="email">Email</label>
            <input
              id="email"
              name="email"
              type="email"
              autoComplete="username"
              maxLength={254}
              value={email}
              disabled={busy}
              onChange={(event) => {
                setEmail(event.target.value)
                setFieldErrors((current) => ({ ...current, email: undefined }))
              }}
              aria-invalid={!!fieldErrors.email}
              aria-describedby={fieldErrors.email ? 'email-error' : undefined}
              required
            />
            {fieldErrors.email ? (
              <p className="field-error" id="email-error">
                {fieldErrors.email}
              </p>
            ) : null}
            <label htmlFor="password">Password</label>
            <div className="password-field">
              <input
                id="password"
                name="password"
                type={visible ? 'text' : 'password'}
                autoComplete="current-password"
                maxLength={128}
                value={password}
                disabled={busy}
                onChange={(event) => {
                  setPassword(event.target.value)
                  setFieldErrors((current) => ({
                    ...current,
                    password: undefined,
                  }))
                }}
                aria-invalid={!!fieldErrors.password}
                aria-describedby={
                  fieldErrors.password ? 'password-error' : undefined
                }
                required
              />
              <button
                type="button"
                className="password-toggle"
                onClick={() => setVisible(!visible)}
                aria-pressed={visible}
                aria-label={visible ? 'Hide password' : 'Show password'}
              >
                {visible ? 'Hide' : 'Show'}
              </button>
            </div>
            {fieldErrors.password ? (
              <p className="field-error" id="password-error">
                {fieldErrors.password}
              </p>
            ) : null}
            {error ? (
              <p className="form-error" role="alert">
                {error}
              </p>
            ) : null}
            <button
              className="check-button sign-in"
              type="submit"
              disabled={busy}
            >
              {busy ? 'Signing in…' : 'Sign in'}
              <span aria-hidden="true">↗</span>
            </button>
            <p className="login-help">
              Use the account provided for your demo workspace. Customer
              accounts sign in through the Android Card app.
            </p>
          </form>
          <p className="simulation-note">
            TapFlow is a simulated payment system. No real transactions are
            processed.
          </p>
        </section>
        <aside className="login-art" aria-label="TapFlow demo">
          <div className="tap-illustration" aria-hidden="true">
            <div className="tap-ring ring-outer" />
            <div className="tap-ring ring-inner" />
            <div className="demo-card">
              <span>TAPFLOW</span>
              <span className="card-symbol">)))</span>
              <div>
                Made for a single tap.<span>SIMULATED VALUE ONLY</span>
              </div>
            </div>
          </div>
          <div className="login-art-copy">
            <p className="eyebrow">TWO PHONES. ONE CONNECTED SYSTEM.</p>
            <h2>
              From the first tap
              <br />
              to the full picture.
            </h2>
            <p>A considered space for a contactless demo.</p>
          </div>
        </aside>
      </main>
    </div>
  )
}
function Workspace({ denied = false }: { denied?: boolean }) {
  const session = useSession()
  const { merchantId } = useParams()
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const user = session.user!
  const membership = user.memberships.find(
    (item) => item.merchantId === merchantId,
  )
  const merchant = useQuery({
    queryKey: ['merchants', merchantId, 'metadata'],
    queryFn: ({ signal }) =>
      session.client.get(`/merchants/${merchantId}`, merchantSchema, signal),
    enabled: !!membership && !denied,
    retry: false,
  })
  if (
    !merchantId &&
    !denied &&
    user.accountType === 'MERCHANT' &&
    user.memberships[0]
  )
    return (
      <Navigate
        to={`/app/merchants/${user.memberships[0].merchantId}/overview`}
        replace
      />
    )
  const unavailable = denied || !membership || user.accountType !== 'MERCHANT'
  async function signOut() {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await session.client.logout()
    } catch {
      setError('We could not connect to sign you out. Please try again.')
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="workspace">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <aside className="sidebar" aria-label="Workspace navigation">
        <Brand />
        <div className="workspace-label">MERCHANT WORKSPACE</div>
        <nav>
          <Link className="current-section" to="/app">
            Overview
          </Link>
          <span className="nav-pending">Transactions</span>
          <span className="nav-pending">Terminals</span>
          {membership?.role === 'OWNER' ? (
            <span className="nav-pending">Audit log</span>
          ) : null}
        </nav>
        <div className="sidebar-note">
          <span className="eyebrow">SIMULATED PAYMENTS</span>
          <p>Designed to connect.</p>
          <span>No real money moves.</span>
        </div>
        <div className="build-label">Signed in securely</div>
      </aside>
      <div className="content">
        <header className="topbar">
          <label className="merchant-select">
            Merchant
            <select
              aria-label="Merchant"
              value={membership?.merchantId ?? ''}
              onChange={(event) =>
                navigate(`/app/merchants/${event.target.value}/overview`)
              }
            >
              {!membership ? (
                <option value="">Choose a workspace</option>
              ) : null}
              {user.memberships.map((item) => (
                <option value={item.merchantId} key={item.merchantId}>
                  {item.merchantName}
                </option>
              ))}
            </select>
          </label>
          <DemoBadge />
        </header>
        <main id="main">
          <div className="account-row">
            <span>{user.email}</span>
            <button
              className="text-button"
              onClick={() => void signOut()}
              disabled={busy}
            >
              {busy ? 'Signing out…' : 'Sign out'}
            </button>
          </div>
          {error ? (
            <p role="alert" className="form-error">
              {error}
            </p>
          ) : null}
          {unavailable ? (
            <section className="workspace-empty">
              <p className="eyebrow">ACCESS</p>
              <h1>Workspace unavailable</h1>
              <p>
                This account does not have access to the requested merchant
                workspace.
              </p>
              {user.accountType === 'CUSTOMER' ? (
                <p>
                  Sign in through the Android Card app to use your customer
                  account.
                </p>
              ) : (
                <Link to="/app">Return to your workspace</Link>
              )}
            </section>
          ) : merchant.isPending ? (
            <p role="status">Loading your workspace…</p>
          ) : merchant.isError ? (
            <section className="workspace-empty">
              <h1>Workspace unavailable</h1>
              <p role="alert">
                {merchant.error instanceof ApiError
                  ? merchant.error.message
                  : 'We could not connect to your workspace.'}
              </p>
              <button
                className="check-button"
                onClick={() => void merchant.refetch()}
                disabled={merchant.isFetching}
              >
                Try again
              </button>
            </section>
          ) : (
            <>
              <div className="page-heading">
                <div>
                  <p className="eyebrow">YOUR WORKSPACE</p>
                  <h1>{merchant.data.name}</h1>
                </div>
                <span className="role-badge">
                  {membership.role === 'OWNER' ? 'Owner' : 'Operator'}
                </span>
              </div>
              <p className="intro">
                You’re connected. This is your space to manage the TapFlow demo.
              </p>
              <section className="connection-panel">
                <div className="connection-copy">
                  <p className="eyebrow">READY FOR WHAT’S NEXT</p>
                  <h2>A workspace for your team.</h2>
                  <p>
                    Your merchant access is verified. Payments, terminal pairing
                    and card enrollment will be available in a later build.
                  </p>
                  <div className="connection-status">
                    <span className="status-dot online" aria-hidden="true" />
                    Workspace connected
                  </div>
                </div>
                <div className="tap-illustration" aria-hidden="true">
                  <div className="tap-ring ring-inner" />
                  <div className="demo-card">
                    <span>TAPFLOW</span>
                    <span className="card-symbol">)))</span>
                    <div>
                      Your next connection.<span>SIMULATED VALUE ONLY</span>
                    </div>
                  </div>
                </div>
              </section>
              <section className="next-section">
                <h2>Before your first demo sale</h2>
                <ol className="steps">
                  <li>
                    <span className="step-number">01</span>
                    <h3>Customer card</h3>
                    <p>
                      Sign in to the Android Card app with a customer account.
                    </p>
                  </li>
                  <li>
                    <span className="step-number">02</span>
                    <h3>Merchant terminal</h3>
                    <p>
                      Sign in to the Android POS app with a merchant account.
                    </p>
                  </li>
                  <li>
                    <span className="step-number">03</span>
                    <h3>Stay in the loop</h3>
                    <p>
                      Use the merchant selector to move between your authorised
                      workspaces.
                    </p>
                  </li>
                </ol>
              </section>
            </>
          )}
          <footer>
            <span>Payments are not enabled yet.</span>
            <span>
              TapFlow is an educational simulator. No real money moves.
            </span>
          </footer>
        </main>
      </div>
    </div>
  )
}
