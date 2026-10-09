import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AcceptInvite, RequireAuth, ResetPassword, SignIn } from './pages/Auth'
import { AppShell } from './ui/AppShell'

const Devices = lazy(() => import('./pages/Devices'))
const Exports = lazy(() => import('./pages/Exports'))
const FieldDetail = lazy(() => import('./pages/FieldDetail'))
const Fields = lazy(() => import('./pages/Fields'))
const Flights = lazy(() => import('./pages/Flights'))
const Landing = lazy(() => import('./pages/Landing'))
const Overview = lazy(() => import('./pages/Overview'))
const Review = lazy(() => import('./pages/Review'))
const Rotation = lazy(() => import('./pages/Rotation'))
const Settings = lazy(() => import('./pages/Settings'))
const Treatments = lazy(() => import('./pages/Treatments'))
const Verification = lazy(() => import('./pages/Verification'))
const WeedMap = lazy(() => import('./pages/WeedMap'))

export default function App() {
  return (
    <Suspense fallback={null}>
    <Routes>
      <Route path="/" element={<Landing />} />
      <Route path="/signin" element={<SignIn />} />
      <Route path="/accept-invite" element={<AcceptInvite />} />
      <Route path="/reset-password" element={<ResetPassword />} />
      <Route element={<RequireAuth />}>
        <Route path="/app" element={<AppShell />}>
          <Route index element={<Overview />} />
          <Route path="fields" element={<Fields />} />
          <Route path="fields/:fieldId" element={<FieldDetail />} />
          <Route path="weed-map/:fieldId?" element={<WeedMap />} />
          <Route path="flights" element={<Flights />} />
          <Route path="review" element={<Review />} />
          <Route path="treatments" element={<Treatments />} />
          <Route path="rotation/:fieldId?" element={<Rotation />} />
          <Route path="verification/:fieldId?" element={<Verification />} />
          <Route path="exports" element={<Exports />} />
          <Route path="devices" element={<Devices />} />
          <Route path="settings" element={<Settings />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/app" replace />} />
    </Routes>
    </Suspense>
  )
}
