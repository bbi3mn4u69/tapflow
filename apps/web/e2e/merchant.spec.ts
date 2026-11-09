import {
  test,
  expect,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto'
let browserErrors: string[] = []
test.beforeEach(({ page }) => {
  browserErrors = []
  page.on('pageerror', (error) => browserErrors.push(error.message))
})
test.afterEach(() => {
  expect(browserErrors).toEqual([])
})
const password = 'instrumentation-only-passphrase'
async function login(request: APIRequestContext, email: string) {
  const r = await request.post('/api/v1/auth/mobile/login', {
    data: { email, password },
  })
  expect(r.status()).toBe(200)
  return r.json()
}
async function fixture(request: APIRequestContext, amount = 2500) {
  const owner = await login(request, 'owner@example.test')
  const operator = await login(request, 'operator@example.test')
  const customer = await login(request, 'customer@example.test')
  const merchant = owner.user.memberships[0].merchantId
  async function post(
    path: string,
    data: unknown,
    token = operator.accessToken,
  ) {
    const response = await request.post('/api/v1' + path, {
      headers: {
        Authorization: `Bearer ${token}`,
        'Idempotency-Key': randomUUID(),
      },
      data,
    })
    expect(response.ok()).toBe(true)
    return response.json()
  }
  const code = await post(
    `/merchants/${merchant}/terminals/pairing-codes`,
    {},
    owner.accessToken,
  )
  const terminal = await post('/terminals/pair', {
    pairingCode: code.pairingCode,
    installationId: randomUUID(),
    label: 'Browser test counter',
  })
  const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  const card = await post(
    '/cards',
    {
      label: 'Browser demo card',
      algorithm: 'P256_SHA256_ECDSA',
      publicKeySpkiBase64: keys.publicKey
        .export({ type: 'spki', format: 'der' })
        .toString('base64'),
    },
    customer.accessToken,
  )
  const intent = await post(`/merchants/${merchant}/payment-intents`, {
    terminalId: terminal.terminalId,
    amountMinor: amount,
    currency: 'AUD',
  })
  const uuid = (value: string) => Buffer.from(value.replaceAll('-', ''), 'hex')
  const raw = Buffer.alloc(68)
  raw[0] = 1
  uuid(intent.id).copy(raw, 1)
  uuid(merchant).copy(raw, 17)
  raw.writeBigInt64BE(BigInt(amount), 33)
  raw.write('AUD', 41, 'ascii')
  Buffer.from(intent.challenge.nonceBase64, 'base64').copy(raw, 44)
  raw.writeBigInt64BE(BigInt(intent.challenge.expiresAtEpochMillis), 60)
  const proof = sign(
    'sha256',
    Buffer.concat([
      Buffer.from('TAPFLOW-DEMO-V1\0', 'ascii'),
      raw,
      uuid(card.cardId),
    ]),
    keys.privateKey,
  ).toString('base64')
  const approved = await post(
    `/merchants/${merchant}/payment-intents/${intent.id}/authorise`,
    {
      terminalId: terminal.terminalId,
      cardId: card.cardId,
      signatureDerBase64: proof,
    },
  )
  expect(approved.status).toBe('APPROVED')
  return {
    merchant,
    payment: intent.id,
    card: card.cardId,
    customer,
    operator,
    receipt: approved.receiptNumber,
    post,
  }
}
async function browserLogin(page: Page, email = 'owner@example.test') {
  await page.goto('/login')
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: 'TapFlow Demo Store' }),
  ).toBeVisible()
}
test('owner issues partial and full refunds; wallet, report and audit reconcile', async ({
  page,
  request,
}) => {
  const f = await fixture(request)
  await browserLogin(page)
  await expect(
    page.getByText('Net demo revenue', { exact: true }),
  ).toBeVisible()
  const links = await page.locator('.sidebar nav a').all()
  for (let index = 1; index < links.length; index++) {
    const before = (await links[index - 1].boundingBox())!
    const after = (await links[index].boundingBox())!
    expect(after.y).toBeGreaterThanOrEqual(before.y + before.height)
    expect(after.height).toBeGreaterThanOrEqual(44)
  }

  await page
    .getByRole('navigation', { name: 'Merchant pages' })
    .getByRole('link', { name: 'Transactions' })
    .click()
  await page.getByRole('link', { name: f.receipt, exact: true }).click()
  for (const amount of ['10.00', '15.00']) {
    await page.getByRole('button', { name: 'Refund', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'Refund demo payment' })
    await dialog.getByLabel('Refund amount in AUD').fill(amount)
    await dialog
      .getByRole('button', { name: 'Confirm refund', exact: true })
      .click()
    await expect(dialog).not.toBeVisible()
  }
  await expect(page.getByText('FULL', { exact: true })).toBeVisible()
  await expect(
    page.getByRole('button', { name: 'Refund', exact: true }),
  ).toHaveCount(0)
  const wallet = await request.get(`/api/v1/cards/${f.card}/wallet`, {
    headers: { Authorization: `Bearer ${f.customer.accessToken}` },
  })
  expect((await wallet.json()).balanceMinor).toBe(10000)
  await page.evaluate(() => {
    ;(document.activeElement as HTMLElement)?.blur()
    window.scrollTo(0, 0)
  })
  await page.screenshot({
    path: 'test-results/phase6-receipt-desktop.png',
    fullPage: true,
  })
  await page.reload()
  await expect(page.getByText('FULL', { exact: true })).toBeVisible()
  await page
    .getByRole('navigation', { name: 'Merchant pages' })
    .getByRole('link', { name: 'Overview' })
    .click()
  await expect(
    page.locator('.kpi').filter({ hasText: 'Net demo revenue' }),
  ).toContainText('A$0.00')
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({
    path: 'test-results/phase6-overview-desktop.png',
    fullPage: true,
  })
  await page
    .getByRole('navigation', { name: 'Merchant pages' })
    .getByRole('link', { name: 'Audit log' })
    .click()
  await page.getByLabel('Event type').fill('refund.created')
  await expect(
    page.getByRole('cell', { name: 'refund.created', exact: true }),
  ).toHaveCount(2)
  await page.setViewportSize({ width: 360, height: 800 })
  await page.goto(`/app/merchants/${f.merchant}/payments/${f.payment}`)
  await expect(
    page.getByRole('heading', { name: 'Demo receipt' }),
  ).toBeVisible()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
  await page.screenshot({
    path: 'test-results/phase6-receipt-mobile.png',
    fullPage: true,
  })
  await page
    .getByRole('navigation', { name: 'Merchant pages' })
    .getByRole('link', { name: 'Transactions' })
    .click()
  await expect(
    page.getByRole('link', { name: f.receipt, exact: true }),
  ).toBeVisible()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  await expect(
    page.getByRole('button', { name: 'Sign in', exact: true }),
  ).toBeVisible()
})
test('operator deep links cannot refund or read audit; empty and declined filters work', async ({
  page,
  request,
}) => {
  const f = await fixture(request)
  await browserLogin(page, 'operator@example.test')
  await page.goto(`/app/merchants/${f.merchant}/payments/${f.payment}`)
  await expect(
    page.getByRole('heading', { name: 'Demo receipt' }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: 'Refund', exact: true }),
  ).toHaveCount(0)
  const denied = await request.post(
    `/api/v1/merchants/${f.merchant}/payments/${f.payment}/refunds`,
    {
      headers: {
        Authorization: `Bearer ${f.operator.accessToken}`,
        'Idempotency-Key': randomUUID(),
      },
      data: { amountMinor: 1000, reason: 'OTHER' },
    },
  )
  expect(denied.status()).toBe(403)
  await page.goto(`/app/merchants/${f.merchant}/audit`)
  await expect(
    page.getByRole('heading', { name: 'Owner access required' }),
  ).toBeVisible()
  await page.goto(`/app/merchants/${f.merchant}/payments?status=DECLINED`)
  await expect(page.getByText('No payments in this period.')).toBeVisible()
  await expect(page.getByLabel('Payment status')).toHaveValue('DECLINED')
})

test('lost committed refund response recovers through reload without another credit', async ({
  page,
  request,
}) => {
  const f = await fixture(request)
  await browserLogin(page)
  await page.goto(`/app/merchants/${f.merchant}/payments/${f.payment}`)
  await page.route('**/payments/*/refunds', async (route) => {
    const upstream = await route.fetch()
    expect(upstream.status()).toBe(201)
    await route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: '{"demo":true}',
    })
  })
  await page.getByRole('button', { name: 'Refund', exact: true }).click()
  await page.getByLabel('Refund amount in AUD').fill('10.00')
  await page
    .getByRole('button', { name: 'Confirm refund', exact: true })
    .click()
  await expect(page.getByRole('alert')).toContainText(
    'outcome is not confirmed',
  )
  await page.unroute('**/payments/*/refunds')
  await page.reload()
  await expect(
    page.getByRole('dialog', { name: 'Refund demo payment' }),
  ).toBeVisible()
  await expect(page.getByLabel('Refund amount in AUD')).toBeDisabled()
  await page
    .getByRole('button', { name: 'Retry saved refund', exact: true })
    .click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(page.getByText('PARTIAL', { exact: true })).toBeVisible()
  const wallet = await request.get(`/api/v1/cards/${f.card}/wallet`, {
    headers: { Authorization: `Bearer ${f.customer.accessToken}` },
  })
  expect((await wallet.json()).balanceMinor).toBe(8500)
  expect(await page.evaluate(() => sessionStorage.length)).toBe(0)
  await expect(page.locator('.refund-history li')).toHaveCount(1)
})
