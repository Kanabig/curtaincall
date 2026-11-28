import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { errorMessage } from '../api/client'
import { useAuth } from '../auth'
import { Button, Field } from '../components/ui'

export default function LoginPage() {
  const { login } = useAuth()
  const nav = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(email, password)
      nav('/')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card form-card" onSubmit={(e) => void submit(e)}>
      <h1>로그인</h1>
      <Field label="이메일" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      <Field label="비밀번호" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      {error && <p role="alert" className="error">{error}</p>}
      <Button variant="primary" disabled={busy}>로그인</Button>
      <p><Link to="/signup">계정이 없으면 가입</Link></p>
    </form>
  )
}
