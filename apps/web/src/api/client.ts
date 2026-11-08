import { z } from 'zod'
import type { components } from './generated/schema'

const statusSchema = z.object({
  service: z.literal('tapflow-api'),
  phase: z.literal('card-enrollment'),
  demo: z.literal(true),
})

export async function fetchStatus(
  signal?: AbortSignal,
): Promise<components['schemas']['Status']> {
  const base = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
  const response = await fetch(`${base}/status`, { signal })
  if (!response.ok) throw new Error(`API unavailable (${response.status})`)
  return statusSchema.parse(await response.json())
}
