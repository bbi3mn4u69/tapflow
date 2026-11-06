import { describe, expect, it, vi } from 'vitest'
import { fetchStatus } from '../src/api/client'

describe('API status', () => {
  it('reads the actual API response', async () => {
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
    await expect(fetchStatus()).resolves.toEqual({
      service: 'tapflow-api',
      phase: 'foundation',
      demo: true,
    })
  })

  it('reports HTTP failure instead of showing an online API', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('', { status: 503 })),
    )
    await expect(fetchStatus()).rejects.toThrow('API unavailable (503)')
  })

  it('rejects a response without the demo indicator', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            service: 'tapflow-api',
            phase: 'foundation',
            demo: false,
          }),
        ),
      ),
    )
    await expect(fetchStatus()).rejects.toThrow()
  })
})
