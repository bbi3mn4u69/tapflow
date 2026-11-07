import { afterEach, expect, it, vi } from 'vitest'
import { z } from 'zod'
import { SessionClient } from '../src/api/session'

const user = {
  id: 'b480e8a3-2677-4d70-a1c8-e46b9c28c286',
  email: 'owner@example.test',
  accountType: 'MERCHANT',
  memberships: [],
}
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status })
afterEach(() => vi.unstubAllGlobals())
it('shares one refresh across concurrent unauthorized reads and sends the renewed token', async () => {
  let refreshes = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init: RequestInit) => {
      if (input.endsWith('/auth/web/login'))
        return json({
          accessToken: 'old-access',
          expiresInSeconds: 900,
          user,
          demo: true,
        })
      if (input.endsWith('/auth/web/refresh')) {
        refreshes++
        await new Promise((resolve) => setTimeout(resolve, 5))
        expect(new Headers(init.headers).get('X-TapFlow-CSRF')).toBe('1')
        return json({
          accessToken: 'new-access',
          expiresInSeconds: 900,
          user,
          demo: true,
        })
      }
      return new Headers(init.headers).get('Authorization') ===
        'Bearer new-access'
        ? json('private-response')
        : json({}, 401)
    }),
  )
  const client = new SessionClient()
  await client.login('owner@example.test', 'test-only-password')
  const replies = await Promise.all(
    [1, 2, 3].map(() => client.get('/me', z.string())),
  )
  expect(replies).toEqual([
    'private-response',
    'private-response',
    'private-response',
  ])
  expect(refreshes).toBe(1)
  expect(localStorage.length).toBe(0)
  expect(sessionStorage.length).toBe(0)
})
it('stops after a failed refresh and clears private cache and identity', async () => {
  let refreshes = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      if (input.endsWith('/auth/web/login'))
        return json({
          accessToken: 'old-access',
          expiresInSeconds: 900,
          user,
          demo: true,
        })
      if (input.endsWith('/auth/web/refresh')) refreshes++
      return json({}, 401)
    }),
  )
  const clear = vi.fn()
  const client = new SessionClient(clear)
  await client.login('owner@example.test', 'test-only-password')
  await expect(client.get('/me', z.string())).rejects.toThrow('Sign in again')
  expect(client.getSnapshot().user).toBeNull()
  expect(refreshes).toBe(1)
  expect(clear).toHaveBeenCalledTimes(2)
})
it('restoration is deduplicated and malformed sessions never authenticate', async () => {
  let calls = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      calls++
      return json({ accessToken: 'missing-demo-metadata' })
    }),
  )
  const client = new SessionClient()
  await Promise.all([client.restore(), client.restore()])
  expect(calls).toBe(1)
  expect(client.getSnapshot().status).toBe('error')
  expect(client.getSnapshot().user).toBeNull()
})
it('rejects a late read after logout instead of delivering stale merchant data', async () => {
  let release: (response: Response) => void = () => {}
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      if (input.endsWith('/auth/web/login'))
        return json({
          accessToken: 'old-access',
          expiresInSeconds: 900,
          user,
          demo: true,
        })
      if (input.endsWith('/auth/web/logout'))
        return new Response(null, { status: 204 })
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    }),
  )
  const client = new SessionClient()
  await client.login('owner@example.test', 'test-only-password')
  const read = client.get('/merchants/test', z.string())
  const rejection = expect(read).rejects.toThrow('Sign in again')
  await client.logout()
  release(json('private-response'))
  await rejection
})

it('clears a session when a renewed access token is also rejected', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      if (
        input.endsWith('/auth/web/login') ||
        input.endsWith('/auth/web/refresh')
      )
        return json({
          accessToken: 'rejected-access',
          expiresInSeconds: 900,
          user,
          demo: true,
        })
      return json({}, 401)
    }),
  )
  const client = new SessionClient()
  await client.login('owner@example.test', 'test-only-password')
  await expect(client.get('/me', z.string())).rejects.toThrow('Sign in again')
  expect(client.getSnapshot().user).toBeNull()
})

it('ends session restoration with a recoverable error when the connection stalls', async () => {
  vi.useFakeTimers()
  try {
    vi.spyOn(AbortSignal, 'timeout').mockImplementation((milliseconds) => {
      const controller = new AbortController()
      setTimeout(
        () => controller.abort(new DOMException('Timed out', 'TimeoutError')),
        milliseconds,
      )
      return controller.signal
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_input: string, init: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init.signal?.addEventListener(
              'abort',
              () => reject(init.signal?.reason),
              { once: true },
            )
          }),
      ),
    )
    const client = new SessionClient()
    const restore = client.restore()
    await vi.advanceTimersByTimeAsync(20_000)
    expect(client.getSnapshot().status).toBe('error')
    expect(client.getSnapshot().user).toBeNull()
    await restore
  } finally {
    vi.useRealTimers()
  }
})
