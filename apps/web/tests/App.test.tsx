import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from '../src/App'

const user = {
  id: 'b480e8a3-2677-4d70-a1c8-e46b9c28c286',
  email: 'owner@example.test',
  accountType: 'MERCHANT',
  memberships: [
    {
      merchantId: 'fe251cc9-dc39-4b6c-a279-f2961ef4e790',
      merchantName: 'First store',
      role: 'OWNER',
    },
    {
      merchantId: 'f6ed9ab8-b435-4381-86e6-c574178b3b54',
      merchantName: 'Second store',
      role: 'OPERATOR',
    },
  ],
}
const session = {
  accessToken: 'test-access',
  expiresInSeconds: 900,
  user,
  demo: true,
}
function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}
function mount(path = '/login') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return client
}
afterEach(() => vi.unstubAllGlobals())
it('signs in with accessible labels and exposes only server memberships', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      if (input.endsWith('/auth/web/refresh')) return json({}, 401)
      if (input.endsWith('/auth/web/login')) return json(session)
      return json({
        merchantId: user.memberships[0].merchantId,
        name: 'First store',
        slug: 'first',
        status: 'ACTIVE',
        demo: true,
      })
    }),
  )
  mount()
  expect(screen.getByText('DEMO / NO REAL MONEY')).toBeVisible()
  await screen.findByRole('button', { name: 'Sign in' })
  fireEvent.change(screen.getByLabelText('Email'), {
    target: { value: 'owner@example.test' },
  })
  fireEvent.change(screen.getByLabelText('Password'), {
    target: { value: 'test-only-password' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  expect(
    await screen.findByRole('heading', { name: 'First store' }),
  ).toBeVisible()
  expect(screen.getByRole('combobox', { name: 'Merchant' })).toBeVisible()
  expect(screen.getByRole('button', { name: 'Sign out' })).toBeEnabled()
  expect(localStorage.length).toBe(0)
})
it('shows a retry when session restoration cannot reach the API', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
  mount('/app')
  expect(await screen.findByRole('alert')).toHaveTextContent('connect')
  expect(screen.getByRole('button', { name: 'Try again' })).toBeEnabled()
})
it('rejects an unknown merchant route and clears cached merchant data on logout', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      if (input.endsWith('/auth/web/refresh')) return json(session)
      if (input.endsWith('/auth/web/logout'))
        return new Response(null, { status: 204 })
      return json({}, 404)
    }),
  )
  const cache = mount('/app/merchants/unknown/overview')
  expect(
    await screen.findByRole('heading', { name: 'Workspace unavailable' }),
  ).toBeVisible()
  cache.setQueryData(['private', 'merchant'], {
    secret: 'test-only-private-data',
  })
  fireEvent.click(screen.getByRole('button', { name: 'Sign out' }))
  await screen.findByRole('button', { name: 'Sign in' })
  await waitFor(() =>
    expect(cache.getQueryData(['private', 'merchant'])).toBeUndefined(),
  )
})
