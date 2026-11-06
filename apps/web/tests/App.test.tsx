import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import App from '../src/App'

afterEach(() => vi.unstubAllGlobals())

it('shows the demo boundary and live API status', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          service: 'tapflow-api',
          phase: 'foundation',
          demo: true,
        }),
      ),
    ),
  )
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  )
  expect(screen.getByText('DEMO / NO REAL MONEY')).toBeVisible()
  expect(await screen.findByText('API connected')).toBeVisible()
  expect(screen.getByText('Payments are not enabled yet.')).toBeVisible()
  client.clear()
})

it('shows an actionable error when the API is offline', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
  )
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('API unavailable')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Check connection' })).toBeEnabled()
  client.clear()
})
