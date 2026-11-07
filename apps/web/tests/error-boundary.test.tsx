import { type ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'

import { ErrorBoundary } from '../src/ErrorBoundary'
it('offers a safe recovery screen after a render failure without exposing the error', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  function Broken(): ReactNode {
    throw new Error('private-error-detail-must-not-be-rendered')
  }
  render(
    <ErrorBoundary>
      <Broken />
    </ErrorBoundary>,
  )
  expect(screen.getByRole('alert')).toBeVisible()
  expect(
    screen.getByRole('link', { name: 'Reload workspace' }),
  ).toHaveAttribute('href', '/app')
  expect(document.body.textContent).not.toContain(
    'private-error-detail-must-not-be-rendered',
  )
  expect(screen.getByText('DEMO / NO REAL MONEY')).toBeVisible()
})
