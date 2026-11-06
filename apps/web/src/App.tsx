import { useQuery } from '@tanstack/react-query'
import { fetchStatus } from './api/client'

export default function App() {
  const status = useQuery({
    queryKey: ['system', 'status'],
    queryFn: ({ signal }) => fetchStatus(signal),
    retry: false,
    staleTime: 30_000,
  })

  return (
    <div className="workspace">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <aside className="sidebar" aria-label="Workspace information">
        <a href="/" className="brand" aria-label="TapFlow workspace">
          <span className="brand-mark" aria-hidden="true">
            t<span>f</span>
          </span>
          <span>
            tapflow<span className="brand-dot">.</span>
          </span>
        </a>
        <div className="workspace-label">MERCHANT WORKSPACE</div>
        <div className="current-section">
          <span aria-hidden="true">◫</span> Getting started
        </div>
        <div className="sidebar-note">
          <span className="eyebrow">BUILT FOR THE DEMO</span>
          <p>
            Two phones.
            <br />
            One connected system.
          </p>
          <span>Android · Kotlin · PostgreSQL</span>
        </div>
        <div className="build-label">
          Development build <span>0.1.0</span>
        </div>
      </aside>

      <div className="content">
        <header className="topbar">
          <span className="breadcrumb">
            Workspace <span aria-hidden="true">/</span> Getting started
          </span>
          <span className="demo-badge">DEMO / NO REAL MONEY</span>
        </header>
        <main id="main">
          <div className="page-heading">
            <div>
              <p className="eyebrow">LET’S GET CONNECTED</p>
              <h1>
                Good things start
                <br />
                with a single tap.
              </h1>
            </div>
            <span className="phase-label">
              Phase 00 <span>Foundation</span>
            </span>
          </div>
          <p className="intro">
            A clear workspace for your demo payments, terminals, and team. We’re
            laying the foundation first.
          </p>

          <section
            className="connection-panel"
            aria-labelledby="connection-title"
          >
            <div className="connection-copy">
              <span className="eyebrow">YOUR CONNECTION</span>
              <h2 id="connection-title">One API. Every touchpoint.</h2>
              <p>
                The customer card, merchant terminal, and this workspace connect
                to the same Kotlin backend.
              </p>
              <div
                className="connection-status"
                role="status"
                aria-live="polite"
              >
                <span
                  className={`status-dot ${status.isSuccess ? 'online' : ''}`}
                  aria-hidden="true"
                />
                {status.isPending
                  ? 'Checking API connection…'
                  : status.isError
                    ? 'API unavailable'
                    : 'API connected'}
              </div>
              {status.isError ? (
                <p className="connection-help">
                  Start the payment API on port 8080, then check again.
                </p>
              ) : null}
              <button
                className="check-button"
                onClick={() => void status.refetch()}
                disabled={status.isFetching}
              >
                {status.isFetching ? 'Checking…' : 'Check connection'}{' '}
                <span aria-hidden="true">↗</span>
              </button>
            </div>
            <div className="tap-illustration" aria-hidden="true">
              <div className="tap-ring ring-outer" />
              <div className="tap-ring ring-inner" />
              <div className="demo-card">
                <span>TAPFLOW</span>
                <span className="card-symbol">)))</span>
                <div>
                  Demo card<span>SIMULATED VALUE ONLY</span>
                </div>
              </div>
              <span className="illustration-caption">DESIGNED TO CONNECT</span>
            </div>
          </section>

          <section aria-labelledby="next-title" className="next-section">
            <div className="section-heading">
              <h2 id="next-title">The path to your first tap</h2>
              <span>BUILT STEP BY STEP</span>
            </div>
            <ol className="steps">
              <li>
                <span className="step-number">01</span>
                <h3>Connect your workspace</h3>
                <p>Database, secure sign-in, and merchant access come next.</p>
                <span className="step-label">UP NEXT</span>
              </li>
              <li>
                <span className="step-number">02</span>
                <h3>Bring two phones</h3>
                <p>Enroll a customer demo card and pair a merchant terminal.</p>
                <span className="step-label">ANDROID + NFC</span>
              </li>
              <li>
                <span className="step-number">03</span>
                <h3>Make your first demo sale</h3>
                <p>Tap, verify, and follow the payment through to a refund.</p>
                <span className="step-label">END TO END</span>
              </li>
            </ol>
          </section>
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
