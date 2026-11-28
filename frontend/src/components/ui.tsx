import type { ButtonHTMLAttributes, HTMLAttributes, InputHTMLAttributes, ReactNode } from 'react'

const cx = (...names: (string | undefined)[]) => names.filter(Boolean).join(' ')

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' }

export function Button({ variant = 'secondary', className, ...props }: ButtonProps) {
  return <button {...props} className={cx('btn', variant === 'secondary' ? undefined : `btn-${variant}`, className)} />
}

type FieldProps = InputHTMLAttributes<HTMLInputElement> & { label: string; hint?: string }

export function Field({ label, hint, ...props }: FieldProps) {
  return (
    <label className="field">
      {label}
      <input {...props} />
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  )
}

export function Chip({ pressed, className, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { pressed: boolean }) {
  return <button type="button" {...props} aria-pressed={pressed} className={cx('chip', className)} />
}

export type Tone = 'neutral' | 'success' | 'warn' | 'danger'

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={cx('badge', tone === 'neutral' ? undefined : `badge-${tone}`)}>{children}</span>
}

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div {...props} className={cx('card', className)} />
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="card empty">
      <strong>{title}</strong>
      {children}
    </div>
  )
}
