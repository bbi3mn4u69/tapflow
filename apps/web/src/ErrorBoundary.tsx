import { Component, type ReactNode } from 'react'

export class ErrorBoundary extends Component<
  { children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false }
  static getDerivedStateFromError() {
    return { failed: true }
  }
  render() {
    if (!this.state.failed) return this.props.children
    return (
      <main className="session-message">
        <span className="demo-badge">DEMO / NO REAL MONEY</span>
        <h1>Let’s try that again.</h1>
        <p role="alert">
          The workspace could not be displayed. Reload to reconnect.
        </p>
        <a className="check-button" href="/app">
          Reload workspace
        </a>
      </main>
    )
  }
}
