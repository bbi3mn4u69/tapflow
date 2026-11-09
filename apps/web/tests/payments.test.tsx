import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from '../src/App'
const merchant = 'fe251cc9-dc39-4b6c-a279-f2961ef4e790'
const payment = 'e952d9fc-e8ba-4d2d-81e7-dc9725a4b566'
const row = {
  paymentId: payment,
  merchantId: merchant,
  terminalId: 'd3e94039-119c-4d70-969e-fc3b6edec7ad',
  terminalLabel: 'Front counter',
  amountMinor: 2500,
  refundedAmountMinor: 0,
  netAmountMinor: 2500,
  refundStatus: 'NONE',
  status: 'APPROVED',
  decisionCode: 'APPROVED',
  currency: 'AUD',
  receiptNumber: 'TF-TEST',
  createdAt: '2026-11-09T01:00:00Z',
  authorisedAt: '2026-11-09T01:00:01Z',
  demo: true,
}
function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}
function mount(
  path: string,
  role = 'OWNER',
  handler: (path: string, init?: RequestInit) => Promise<Response> = async () =>
    json({}, 404),
) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init?: RequestInit) => {
      if (path.endsWith('/auth/web/refresh'))
        return json({
          accessToken: 'fixture',
          expiresInSeconds: 900,
          demo: true,
          user: {
            id: 'b480e8a3-2677-4d70-a1c8-e46b9c28c286',
            email: 'staff@example.test',
            accountType: 'MERCHANT',
            memberships: [
              { merchantId: merchant, merchantName: 'Test store', role },
            ],
          },
        })
      if (path === `/api/v1/merchants/${merchant}`)
        return json({
          merchantId: merchant,
          name: 'Test store',
          slug: 'test',
          status: 'ACTIVE',
          demo: true,
        })
      return handler(path, init)
    }),
  )
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[`/app/merchants/${merchant}/${path}`]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
afterEach(() => vi.unstubAllGlobals())
it('shows live transaction amounts and filters the requested status', async () => {
  const reads: string[] = []
  mount('payments', 'OWNER', async (path) => {
    reads.push(path)
    return json({ items: [row], nextCursor: null, demo: true })
  })
  expect(await screen.findByRole('link', { name: 'TF-TEST' })).toBeVisible()
  expect(screen.getByText('Front counter')).toBeVisible()
  fireEvent.change(screen.getByLabelText('Payment status'), {
    target: { value: 'DECLINED' },
  })
  await waitFor(() =>
    expect(reads.some((path) => path.includes('status=DECLINED'))).toBe(true),
  )
})
it('owner confirms an exact-cent refund and refreshes remaining amount', async () => {
  let refunded = 0
  const keys: string[] = []
  mount(`payments/${payment}`, 'OWNER', async (path, init) => {
    if (path.endsWith('/refunds')) {
      keys.push(new Headers(init?.headers).get('Idempotency-Key')!)
      const request = JSON.parse(init!.body as string)
      expect(request.amountMinor).toBe(1000)
      refunded = 1000
      return json(
        {
          refundId: 'b667921a-901d-4d29-9ac4-03c013ae6bc1',
          paymentId: payment,
          amountMinor: 1000,
          currency: 'AUD',
          refundStatus: 'PARTIAL',
          totalRefundedMinor: 1000,
          demo: true,
        },
        201,
      )
    }
    return json({
      ...row,
      refundedAmountMinor: refunded,
      netAmountMinor: 2500 - refunded,
      refundStatus: refunded ? 'PARTIAL' : 'NONE',
      merchantName: 'Test store',
      cardAlias: 'Demo card',
      refunds: refunded
        ? [
            {
              refundId: 'b667921a-901d-4d29-9ac4-03c013ae6bc1',
              paymentId: payment,
              amountMinor: 1000,
              reason: 'CUSTOMER_REQUEST',
              createdAt: '2026-11-09T01:01:00Z',
            },
          ]
        : [],
    })
  })
  fireEvent.click(await screen.findByRole('button', { name: 'Refund' }))
  expect(
    screen.getByRole('dialog', { name: 'Refund demo payment' }),
  ).toBeVisible()
  fireEvent.change(screen.getByLabelText('Refund amount in AUD'), {
    target: { value: '10.00' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Confirm refund' }))
  expect(await screen.findByText('PARTIAL')).toBeVisible()
  expect(keys).toHaveLength(1)
  expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
})
it('operator sees the receipt but cannot open refund or audit controls', async () => {
  mount(`payments/${payment}`, 'OPERATOR', async () =>
    json({
      ...row,
      merchantName: 'Test store',
      cardAlias: 'Demo card',
      refunds: [],
    }),
  )
  expect(
    await screen.findByRole('heading', { name: 'Demo receipt' }),
  ).toBeVisible()
  expect(
    screen.queryByRole('button', { name: 'Refund' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('link', { name: 'Audit log' }),
  ).not.toBeInTheDocument()
})
it('shows an empty transactions state and a retry after a failed read', async () => {
  let offline = true
  mount('payments', 'OWNER', async () =>
    offline ? json({}, 503) : json({ items: [], nextCursor: null, demo: true }),
  )
  expect(await screen.findByRole('alert')).toBeVisible()
  offline = false
  fireEvent.click(screen.getByRole('button', { name: 'Retry payments' }))
  expect(await screen.findByText('No payments in this period.')).toBeVisible()
})
it('loads server dashboard totals and provides the daily data table', async () => {
  mount('overview', 'OWNER', async () =>
    json({
      approvedCount: 1,
      declinedCount: 0,
      grossApprovedMinor: 2500,
      totalRefundedMinor: 1000,
      netApprovedMinor: 1500,
      currency: 'AUD',
      from: '2026-11-01T00:00:00Z',
      to: '2026-11-10T00:00:00Z',
      dailyBuckets: [
        { date: '2026-11-09', approvedCount: 1, grossApprovedMinor: 2500 },
      ],
      recentPayments: [row],
      demo: true,
    }),
  )
  expect(await screen.findByText('A$15.00')).toBeVisible()
  fireEvent.click(screen.getByText('View daily data'))
  expect(
    screen.getByRole('table', { name: 'Daily approved payments' }),
  ).toBeVisible()
})
it('retries an uncertain refund with the same saved key and immutable amount', async () => {
  sessionStorage.clear()
  const keys: string[] = []
  let attempts = 0
  mount(`payments/${payment}`, 'OWNER', async (path, init) => {
    if (path.endsWith('/refunds')) {
      keys.push(new Headers(init?.headers).get('Idempotency-Key')!)
      attempts++
      return attempts === 1
        ? json({}, 503)
        : json(
            {
              refundId: 'b667921a-901d-4b29-9ac4-03c013ae6bc1',
              paymentId: payment,
              amountMinor: 1000,
              currency: 'AUD',
              refundStatus: 'PARTIAL',
              totalRefundedMinor: 1000,
              demo: true,
            },
            201,
          )
    }
    return json({
      ...row,
      merchantName: 'Test store',
      cardAlias: 'Demo card',
      refunds: [],
    })
  })
  fireEvent.click(await screen.findByRole('button', { name: 'Refund' }))
  fireEvent.change(screen.getByLabelText('Refund amount in AUD'), {
    target: { value: '10' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Confirm refund' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'outcome is not confirmed',
  )
  expect(screen.getByLabelText('Refund amount in AUD')).toBeDisabled()
  expect(sessionStorage.length).toBe(1)
  fireEvent.click(screen.getByRole('button', { name: 'Retry saved refund' }))
  await waitFor(() => expect(keys).toHaveLength(2))
  expect(keys[0]).toBe(keys[1])
  await waitFor(() => expect(sessionStorage.length).toBe(0))
})
it('refuses fractional cents and amounts above the remaining refund', async () => {
  mount(`payments/${payment}`, 'OWNER', async () =>
    json({
      ...row,
      merchantName: 'Test store',
      cardAlias: 'Demo card',
      refunds: [],
    }),
  )
  fireEvent.click(await screen.findByRole('button', { name: 'Refund' }))
  for (const value of ['25.01', '0', '1.001', '1e2']) {
    fireEvent.change(screen.getByLabelText('Refund amount in AUD'), {
      target: { value },
    })
    expect(
      screen.getByRole('button', { name: 'Confirm refund' }),
    ).toBeDisabled()
  }
})
it('blocks the operator audit deep link without requesting audit data', async () => {
  const paths: string[] = []
  mount('audit', 'OPERATOR', async (path) => {
    paths.push(path)
    return json({}, 403)
  })
  expect(
    await screen.findByRole('heading', { name: 'Owner access required' }),
  ).toBeVisible()
  expect(paths.some((path) => path.includes('audit-events'))).toBe(false)
})
it('preserves an uncertain refund when a later retry loses owner access', async () => {
  sessionStorage.clear()
  let attempts = 0
  mount(`payments/${payment}`, 'OWNER', async (path) =>
    path.endsWith('/refunds')
      ? json({}, ++attempts === 1 ? 503 : 403)
      : json({
          ...row,
          merchantName: 'Test store',
          cardAlias: 'Demo card',
          refunds: [],
        }),
  )
  fireEvent.click(await screen.findByRole('button', { name: 'Refund' }))
  fireEvent.click(screen.getByRole('button', { name: 'Confirm refund' }))
  await screen.findByRole('alert')
  const saved = sessionStorage.getItem(sessionStorage.key(0)!)
  fireEvent.click(screen.getByRole('button', { name: 'Retry saved refund' }))
  await waitFor(() => expect(attempts).toBe(2))
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: 'Retry saved refund' }),
    ).toBeEnabled(),
  )
  expect(sessionStorage.getItem(sessionStorage.key(0)!)).toBe(saved)
  sessionStorage.clear()
})
it('rejects an invalid payment reference before requesting a resource', async () => {
  const paths: string[] = []
  mount('payments/not-a-uuid', 'OWNER', async (path) => {
    paths.push(path)
    return json({}, 404)
  })
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Invalid payment reference',
  )
  expect(paths).toEqual([])
})
