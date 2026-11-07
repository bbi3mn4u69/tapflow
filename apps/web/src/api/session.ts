import { z } from 'zod'
import type { components } from './generated/schema'

export const userSchema: z.ZodType<components['schemas']['SessionUser']> =
  z.object({
    id: z.uuid(),
    email: z.email(),
    accountType: z.enum(['CUSTOMER', 'MERCHANT', 'SYSTEM_ADMIN']),
    memberships: z.array(
      z.object({
        merchantId: z.uuid(),
        merchantName: z.string().max(120),
        role: z.enum(['OWNER', 'OPERATOR']),
      }),
    ),
  })
const sessionSchema: z.ZodType<components['schemas']['WebSession']> = z.object({
  accessToken: z.string().min(1),
  expiresInSeconds: z.literal(900),
  user: userSchema,
  demo: z.literal(true),
})
export const merchantSchema: z.ZodType<components['schemas']['Merchant']> =
  z.object({
    merchantId: z.uuid(),
    name: z.string().max(120),
    slug: z.string().max(80),
    status: z.enum(['ACTIVE', 'SUSPENDED']),
    demo: z.literal(true),
  })
export type SessionState = {
  status: 'loading' | 'ready' | 'error'
  user: components['schemas']['SessionUser'] | null
  error: string | null
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly retryAfter: number | null = null,
  ) {
    super(
      status === 401
        ? 'Your session ended. Sign in again.'
        : status === 403
          ? 'You do not have access to this workspace.'
          : status === 404
            ? 'This workspace is unavailable.'
            : status === 429
              ? 'Too many attempts. Please wait before trying again.'
              : 'We could not connect. Please try again.',
    )
  }
}

export class SessionClient {
  private token: string | null = null
  private state: SessionState = { status: 'loading', user: null, error: null }
  private listeners = new Set<() => void>()
  private refreshing: Promise<void> | null = null
  private restoring: Promise<void> | null = null
  private generation = 0
  constructor(
    private readonly clearCache: () => void = () => {},
    private readonly base = import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  ) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }
  private publish(state: SessionState) {
    this.state = state
    this.listeners.forEach((listener) => listener())
  }
  private update(session: components['schemas']['WebSession'] | null) {
    const identity = (user: SessionState['user']) =>
      user
        ? JSON.stringify([user.id, user.accountType, user.memberships])
        : null
    if (identity(this.state.user) !== identity(session?.user ?? null))
      this.clearCache()
    this.token = session?.accessToken ?? null
    this.publish({ status: 'ready', user: session?.user ?? null, error: null })
  }
  private async send(path: string, init: RequestInit = {}) {
    const headers = new Headers(init.headers)
    headers.set('X-Request-ID', crypto.randomUUID())
    if (init.method === 'POST') headers.set('X-TapFlow-CSRF', '1')
    const response = await fetch(`${this.base}${path}`, {
      ...init,
      headers,
      credentials: 'same-origin',
      cache: 'no-store',
    })
    if (!response.ok) {
      const retry = Number(response.headers.get('Retry-After'))
      throw new ApiError(
        response.status,
        Number.isFinite(retry) && retry > 0 ? retry : null,
      )
    }
    return response
  }
  private refresh(): Promise<void> {
    if (this.refreshing) return this.refreshing
    const generation = this.generation
    this.refreshing = (async () => {
      try {
        const response = await this.send('/auth/web/refresh', {
          method: 'POST',
        })
        const session = sessionSchema.parse(await response.json())
        if (generation !== this.generation) throw new ApiError(401)
        this.update(session)
      } catch (error) {
        if (
          error instanceof ApiError &&
          error.status === 401 &&
          generation === this.generation
        )
          this.update(null)
        throw error
      } finally {
        this.refreshing = null
      }
    })()
    return this.refreshing
  }
  restore(): Promise<void> {
    if (this.restoring) return this.restoring
    this.publish({ status: 'loading', user: null, error: null })
    this.restoring = this.refresh()
      .catch((error: unknown) => {
        if (!(error instanceof ApiError && error.status === 401))
          this.publish({
            status: 'error',
            user: null,
            error: 'We could not connect to restore your session.',
          })
      })
      .finally(() => {
        this.restoring = null
      })
    return this.restoring
  }
  async login(email: string, password: string) {
    if (this.refreshing) await this.refreshing.catch(() => {})
    this.generation++
    const response = await this.send('/auth/web/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    })
    this.update(sessionSchema.parse(await response.json()))
  }
  async logout() {
    if (this.refreshing) await this.refreshing.catch(() => {})
    await this.send('/auth/web/logout', { method: 'POST' })
    this.generation++
    this.update(null)
    this.clearCache()
  }
  async get<T>(
    path: string,
    schema: z.ZodType<T>,
    signal?: AbortSignal,
  ): Promise<T> {
    const before = this.token
    const generation = this.generation
    let response: Response
    try {
      response = await this.send(path, {
        signal,
        headers: before ? { Authorization: `Bearer ${before}` } : {},
      })
    } catch (error) {
      if (
        !(error instanceof ApiError && error.status === 401) ||
        generation !== this.generation
      )
        throw error
      if (this.token === before) await this.refresh()
      if (!this.token || generation !== this.generation) throw new ApiError(401)
      try {
        response = await this.send(path, {
          signal,
          headers: { Authorization: `Bearer ${this.token}` },
        })
      } catch (retryError) {
        if (
          retryError instanceof ApiError &&
          retryError.status === 401 &&
          generation === this.generation
        )
          this.update(null)
        throw retryError
      }
    }
    if (generation !== this.generation) throw new ApiError(401)
    return schema.parse(await response.json())
  }
}
