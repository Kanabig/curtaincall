import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { errorMessage } from '../api/client'
import { useAuth } from '../auth'
import { Button, Field } from '../components/ui'

export default function SignUpPage() {
  const { login } = useAuth()
  const nav = useNavigate()
  const [form, setForm] = useState({ email: '', password: '', name: '' })
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [k]: e.target.value })

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.signUp(form)
      await login(form.email, form.password)
      nav('/')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card form-card" onSubmit={(e) => void submit(e)}>
      <h1>가입</h1>
      <Field label="이메일" type="email" value={form.email} onChange={set('email')} maxLength={254} required />
      <Field label="비밀번호 (8~72자)" type="password" value={form.password} onChange={set('password')} minLength={8} maxLength={72} required />
      <Field label="이름" value={form.name} onChange={set('name')} maxLength={50} required />
      {error && <p role="alert" className="error">{error}</p>}
      <Button variant="primary" disabled={busy}>가입</Button>
    </form>
  )
}
