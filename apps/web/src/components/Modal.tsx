import { useEffect, useRef } from 'react'
export default function Modal({
  children,
  close,
  title,
  label,
}: {
  children: React.ReactNode
  close: () => void
  title: string
  label: string
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const previous = useRef<HTMLElement | null>(null)
  useEffect(() => {
    if (!previous.current && document.activeElement instanceof HTMLElement)
      previous.current = document.activeElement
    dialog.current?.showModal()
    return () => {
      if (previous.current?.isConnected) previous.current.focus()
    }
  }, [])
  return (
    <dialog
      className="pair-dialog"
      ref={dialog}
      aria-label={title}
      onClose={close}
      onCancel={(event) => {
        event.preventDefault()
        close()
      }}
    >
      <div className="dialog-heading">
        <h2>{title}</h2>
        <button className="text-button" aria-label={label} onClick={close}>
          Close
        </button>
      </div>
      <p className="eyebrow">DEMO / NO REAL MONEY</p>
      {children}
    </dialog>
  )
}
