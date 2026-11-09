import { z } from 'zod'
import type { components } from '../../api/generated/schema'
export const total = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER)
const stamp = z.iso.datetime({ precision: null })
export const paymentStatus = z.enum([
  'AWAITING_TAP',
  'APPROVED',
  'DECLINED',
  'EXPIRED',
  'CANCELLED',
])
export const summary = z.object({
  paymentId: z.uuid(),
  merchantId: z.uuid(),
  terminalId: z.uuid(),
  terminalLabel: z.string().max(80),
  amountMinor: total,
  refundedAmountMinor: total,
  netAmountMinor: total,
  refundStatus: z.enum(['NONE', 'PARTIAL', 'FULL']),
  status: paymentStatus,
  decisionCode: z
    .enum(['APPROVED', 'CARD_BLOCKED', 'INSUFFICIENT_FUNDS'])
    .nullable(),
  currency: z.literal('AUD'),
  receiptNumber: z.string().max(80).nullable(),
  createdAt: stamp,
  authorisedAt: stamp.nullable(),
  demo: z.literal(true),
}) satisfies z.ZodType<components['schemas']['PaymentSummary']>
export const page = z.object({
  items: z.array(summary).max(100),
  nextCursor: z.string().nullable(),
  demo: z.literal(true),
})
const refund = z.object({
  refundId: z.uuid(),
  paymentId: z.uuid(),
  amountMinor: total,
  reason: z.enum(['CUSTOMER_REQUEST', 'DUPLICATE', 'OTHER']),
  createdAt: stamp,
})
export const detail: z.ZodType<components['schemas']['PaymentDetail']> =
  z.object({
    ...summary.shape,
    merchantName: z.string().max(120),
    cardAlias: z.string().max(80).nullable(),
    refunds: z.array(refund),
  })
export const refundResult = z.object({
  refundId: z.uuid(),
  paymentId: z.uuid(),
  amountMinor: total,
  currency: z.literal('AUD'),
  refundStatus: z.enum(['NONE', 'PARTIAL', 'FULL']),
  totalRefundedMinor: total,
  demo: z.literal(true),
})
export const dashboard: z.ZodType<components['schemas']['Dashboard']> =
  z.object({
    approvedCount: total,
    declinedCount: total,
    grossApprovedMinor: total,
    totalRefundedMinor: total,
    netApprovedMinor: total,
    currency: z.literal('AUD'),
    from: stamp,
    to: stamp,
    dailyBuckets: z
      .array(
        z.object({
          date: z.iso.date(),
          approvedCount: total,
          grossApprovedMinor: total,
        }),
      )
      .max(367),
    recentPayments: z.array(summary).max(10),
    demo: z.literal(true),
  })
export const audit = z.object({
  items: z
    .array(
      z.object({
        id: z.uuid(),
        actorUserId: z.uuid().nullable(),
        merchantId: z.uuid().nullable(),
        eventType: z.string().max(80),
        resourceType: z.string().max(40),
        resourceId: z.uuid().nullable(),
        details: z.record(z.string(), z.unknown()),
        requestId: z.uuid(),
        createdAt: stamp,
      }),
    )
    .max(100),
  nextCursor: z.string().nullable(),
  demo: z.literal(true),
})
export function money(value: number) {
  return (
    'A$' +
    Math.trunc(value / 100).toLocaleString('en-AU') +
    '.' +
    String(value % 100).padStart(2, '0')
  )
}
export function cents(value: string): number | null {
  if (!/^[0-9]{1,5}(\.[0-9]{1,2})?$/.test(value)) return null
  const [d, c = ''] = value.split('.')
  const result = Number(d) * 100 + Number(c.padEnd(2, '0'))
  return result > 0 && result <= 1000000 ? result : null
}
const dates = new Intl.DateTimeFormat('en-AU', {
  timeZone: 'Australia/Melbourne',
  dateStyle: 'medium',
  timeStyle: 'short',
})
export function date(value: string) {
  return dates.format(new Date(value))
}
