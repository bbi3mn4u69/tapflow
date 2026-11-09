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
      if (input.includes('/dashboard'))
        return json({
          approvedCount: 0,
          declinedCount: 0,
          grossApprovedMinor: 0,
          totalRefundedMinor: 0,
          netApprovedMinor: 0,
          currency: 'AUD',
          from: '2026-11-01T00:00:00Z',
          to: '2026-11-10T00:00:00Z',
          dailyBuckets: [],
          recentPayments: [],
          demo: true,
        })
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

it('owner reveals a pairing code only in its modal and clears it on close', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      if (input.endsWith('/auth/web/refresh')) return json(session)
      if (input.endsWith('/terminals/pairing-codes'))
        return json(
          {
            pairingCode: 'ABCDEF1234567',
            merchantId: user.memberships[0].merchantId,
            expiresAt: new Date(Date.now() + 600000).toISOString(),
            demo: true,
          },
          201,
        )
      if (input.includes('/terminals'))
        return json({ items: [], nextCursor: null, demo: true })
      return json({
        merchantId: user.memberships[0].merchantId,
        name: 'First store',
        slug: 'first',
        status: 'ACTIVE',
        demo: true,
      })
    }),
  )
  const cache = mount(
    `/app/merchants/${user.memberships[0].merchantId}/terminals`,
  )
  const trigger = await screen.findByRole('button', {
    name: 'Create pairing code',
  })
  trigger.focus()
  fireEvent.click(trigger)
  expect(await screen.findByRole('dialog')).toBeVisible()
  expect(await screen.findByText('ABCDEF1234567')).toBeVisible()
  expect(
    JSON.stringify(
      cache
        .getQueryCache()
        .getAll()
        .map((q) => q.state.data),
    ),
  ).not.toContain('ABCDEF1234567')
  fireEvent.click(screen.getByRole('button', { name: 'Close pairing code' }))
  await waitFor(() =>
    expect(screen.queryByText('ABCDEF1234567')).not.toBeInTheDocument(),
  )
  expect(trigger).toHaveFocus()
  expect(localStorage.length).toBe(0)
})

it('operator can inspect terminals without seeing owner pairing actions', async () => {
  const operatorSession = {
    ...session,
    user: {
      ...user,
      memberships: [{ ...user.memberships[0], role: 'OPERATOR' }],
    },
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      if (input.endsWith('/auth/web/refresh')) return json(operatorSession)
      if (input.includes('/terminals'))
        return json({ items: [], nextCursor: null, demo: true })
      return json({
        merchantId: user.memberships[0].merchantId,
        name: 'First store',
        slug: 'first',
        status: 'ACTIVE',
        demo: true,
      })
    }),
  )
  mount(`/app/merchants/${user.memberships[0].merchantId}/terminals`)
  expect(await screen.findByText('No terminals paired yet.')).toBeVisible()
  expect(
    screen.queryByRole('button', { name: 'Create pairing code' }),
  ).not.toBeInTheDocument()
})

it('pairing-code retry keeps its idempotency key after a lost response', async () => {
  const keys: (string | null)[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init: RequestInit) => {
      if (input.endsWith('/auth/web/refresh')) return json(session)
      if (input.endsWith('/terminals/pairing-codes')) {
        keys.push(new Headers(init.headers).get('Idempotency-Key'))
        if (keys.length === 1) throw new TypeError('response lost')
        return json(
          {
            pairingCode: 'ABCDEF1234567',
            merchantId: user.memberships[0].merchantId,
            expiresAt: new Date(Date.now() + 600000).toISOString(),
            demo: true,
          },
          201,
        )
      }
      if (input.includes('/terminals'))
        return json({ items: [], nextCursor: null, demo: true })
      return json({
        merchantId: user.memberships[0].merchantId,
        name: 'First store',
        slug: 'first',
        status: 'ACTIVE',
        demo: true,
      })
    }),
  )
  mount(`/app/merchants/${user.memberships[0].merchantId}/terminals`)
  fireEvent.click(
    await screen.findByRole('button', { name: 'Create pairing code' }),
  )
  fireEvent.click(
    await screen.findByRole('button', { name: 'Retry code request' }),
  )
  expect(await screen.findByText('ABCDEF1234567')).toBeVisible()
  expect(keys).toHaveLength(2)
  expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
  expect(keys[1]).toBe(keys[0])
})

it('pairing countdown starts from response time instead of stale modal-open time', async () => {
  let now = 1800000000000
  const clock = vi.spyOn(Date, 'now').mockImplementation(() => now)
  try {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: string) => {
        if (input.endsWith('/auth/web/refresh')) return json(session)
        if (input.endsWith('/terminals/pairing-codes')) {
          now += 10000
          return json(
            {
              pairingCode: 'ABCDEF1234567',
              merchantId: user.memberships[0].merchantId,
              expiresAt: new Date(now + 600000).toISOString(),
              demo: true,
            },
            201,
          )
        }
        if (input.includes('/terminals'))
          return json({ items: [], nextCursor: null, demo: true })
        return json({
          merchantId: user.memberships[0].merchantId,
          name: 'First store',
          slug: 'first',
          status: 'ACTIVE',
          demo: true,
        })
      }),
    )
    mount(`/app/merchants/${user.memberships[0].merchantId}/terminals`)
    fireEvent.click(
      await screen.findByRole('button', { name: 'Create pairing code' }),
    )
    expect(await screen.findByRole('timer')).toHaveTextContent(
      'Expires in 10:00',
    )
  } finally {
    clock.mockRestore()
  }
})
