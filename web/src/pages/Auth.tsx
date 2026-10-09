import { ArrowLeft, ArrowRight, Check, KeyRound, LogIn, Mail, UserPlus } from 'lucide-react'
import { useEffect, useState, useSyncExternalStore, type FormEvent, type ReactNode } from 'react'
import { Link, Navigate, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { api, auth, errorMessage, type TokenOut } from '../api/client'
import { BrandMark } from '../ui/brand'
import { Button, Field, Notice, TextInput } from '../ui/kit'
import './auth.css'

const useSignedIn = () => useSyncExternalStore(auth.subscribe, () => auth.signedIn)

/** Guards /app/*: without a session, go to sign-in and come back afterwards. */
export function RequireAuth() {
  const signedIn = useSignedIn()
  const loc = useLocation()
  if (!signedIn) return <Navigate to={`/signin?next=${encodeURIComponent(loc.pathname + loc.search)}`} replace />
  return <Outlet />
}

function Frame({ title, sub, children, foot }: { title: string; sub: ReactNode; children: ReactNode; foot?: ReactNode }) {
  useEffect(() => { const prev = document.title; document.title = `${title} · WeedReaver`; return () => { document.title = prev } }, [title])
  return (
    <div className="auth">
      <aside className="auth__side">
        <Link to="/" className="auth__brand"><BrandMark size={38} bg="#F5F0E6" ink="#143524" /><span>WeedReaver</span></Link>
        <div className="auth__pitch">
          <p className="serif">Know where control failed.</p>
          <span>The dashboard decides what gets sprayed. The phones carry it into the field, offline, and report back what was done.</span>
        </div>
        <span className="auth__small">Pindi Bhattian field station</span>
      </aside>
      <main className="auth__main">
        <div className="auth__card">
          <h1 className="title-l">{title}</h1>
          <p className="body-s auth__sub">{sub}</p>
          {children}
        </div>
        {foot && <div className="auth__foot">{foot}</div>}
      </main>
    </div>
  )
}

function safeNext(next: string | null) {
  return next && next.startsWith('/app') ? next : '/app'
}

export function SignIn() {
  const nav = useNavigate()
  const [params] = useSearchParams()
  const signedIn = useSignedIn()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  if (signedIn) return <Navigate to={safeNext(params.get('next'))} replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true); setError('')
    try {
      await auth.signIn(email.trim(), password)
      nav(safeNext(params.get('next')), { replace: true })
    } catch (err) {
      setError(errorMessage(err)); setBusy(false)
    }
  }
  return (
    <Frame title="Sign in" sub="Use the account the station administrator set up for you."
      foot={<Link to="/" className="link-btn"><ArrowLeft size={14} /> Project website</Link>}>
      <form className="col gap-16" onSubmit={submit}>
        <Field label="Email"><TextInput type="email" autoComplete="username" autoFocus required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@pindibhattian-station.pk" /></Field>
        <Field label="Password"><TextInput type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
        {error && <Notice tone="clay" title={error} />}
        <Button variant="primary" size="lg" block icon={LogIn} loading={busy} type="submit">Sign in</Button>
        <Link to="/reset-password" className="link-btn auth__alt">Forgot your password?</Link>
      </form>
    </Frame>
  )
}

export function AcceptInvite() {
  const nav = useNavigate()
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true); setError('')
    try {
      auth.accept(await api.post<TokenOut>('/auth/accept-invite', { token, name: name.trim(), password }))
      nav('/app', { replace: true })
    } catch (err) {
      setError(errorMessage(err)); setBusy(false)
    }
  }
  return (
    <Frame title="Join the station" sub="You were invited to WeedReaver. Choose the name your colleagues will see and a password.">
      {!token ? <Notice tone="clay" title="This link is missing its invitation code">Open the link from the invitation email again, or ask the administrator for a new one.</Notice> : (
        <form className="col gap-16" onSubmit={submit}>
          <Field label="Your name"><TextInput autoFocus required value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Dr. S. Anjum" /></Field>
          <Field label="Password" hint="At least 8 characters."><TextInput type="password" autoComplete="new-password" required value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
          {error && <Notice tone="clay" title={error} />}
          <Button variant="primary" size="lg" block icon={UserPlus} loading={busy} type="submit">Create account</Button>
        </form>
      )}
    </Frame>
  )
}

export function ResetPassword() {
  const [params] = useSearchParams()
  const token = params.get('token')
  return token ? <ResetConfirm token={token} /> : <ResetRequest />
}

function ResetRequest() {
  const [email, setEmail] = useState('')
  const [busy, setBusy] = useState(false)
  const [sent, setSent] = useState<{ message: string; devToken?: string } | null>(null)
  const [error, setError] = useState('')
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true); setError('')
    try { setSent(await api.post('/auth/password-reset/request', { email: email.trim() })) } catch (err) { setError(errorMessage(err)) }
    setBusy(false)
  }
  return (
    <Frame title="Reset your password" sub="We will email a link to set a new one." foot={<Link to="/signin" className="link-btn"><ArrowLeft size={14} /> Back to sign in</Link>}>
      {sent ? (
        <div className="col gap-16">
          <Notice tone="forest" icon={Mail} title={sent.message} />
          {sent.devToken && <Link className="btn btn--tonal" to={`/reset-password?token=${encodeURIComponent(sent.devToken)}`}>Open the reset link (development) <ArrowRight size={15} /></Link>}
        </div>
      ) : (
        <form className="col gap-16" onSubmit={submit}>
          <Field label="Email"><TextInput type="email" autoComplete="username" autoFocus required value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
          {error && <Notice tone="clay" title={error} />}
          <Button variant="primary" size="lg" block icon={Mail} loading={busy} type="submit">Send the link</Button>
        </form>
      )}
    </Frame>
  )
}

function ResetConfirm({ token }: { token: string }) {
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState('')
  const [error, setError] = useState('')
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true); setError('')
    try { setDone((await api.post<{ message: string }>('/auth/password-reset/confirm', { token, newPassword: password })).message) } catch (err) { setError(errorMessage(err)) }
    setBusy(false)
  }
  return (
    <Frame title="Choose a new password" sub="Other sessions on this account are signed out when you save it.">
      {done ? (
        <div className="col gap-16">
          <Notice tone="forest" icon={Check} title={done} />
          <Link className="btn btn--primary btn--lg btn--block" to="/signin"><LogIn size={18} /> Sign in</Link>
        </div>
      ) : (
        <form className="col gap-16" onSubmit={submit}>
          <Field label="New password" hint="At least 8 characters."><TextInput type="password" autoComplete="new-password" autoFocus required value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
          {error && <Notice tone="clay" title={error} />}
          <Button variant="primary" size="lg" block icon={KeyRound} loading={busy} type="submit">Save password</Button>
        </form>
      )}
    </Frame>
  )
}
