import { Route, Routes } from 'react-router-dom'
import { BottomNav, TopNav } from './components/Nav'
import AdminPage from './pages/AdminPage'
import CheckoutPage from './pages/CheckoutPage'
import LoginPage from './pages/LoginPage'
import PerformanceDetailPage from './pages/PerformanceDetailPage'
import OrderPage from './pages/OrderPage'
import PerformanceListPage from './pages/PerformanceListPage'
import QueuePage from './pages/QueuePage'
import ReservationListPage from './pages/ReservationListPage'
import ReservationPage from './pages/ReservationPage'
import SeatPage from './pages/SeatPage'
import SignUpPage from './pages/SignUpPage'

export default function App() {
  return (
    <>
      <TopNav />
      <main>
        <Routes>
          <Route path="/" element={<PerformanceListPage />} />
          <Route path="/performances/:id" element={<PerformanceDetailPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="/sessions/:sessionId/queue" element={<QueuePage />} />
          <Route path="/sessions/:sessionId/seats" element={<SeatPage />} />
          <Route path="/sessions/:sessionId/checkout" element={<CheckoutPage />} />
          <Route path="/orders/:orderId" element={<OrderPage />} />
          <Route path="/reservations" element={<ReservationListPage />} />
          <Route path="/reservations/:id" element={<ReservationPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignUpPage />} />
          <Route path="*" element={<p>페이지를 찾을 수 없습니다.</p>} />
        </Routes>
      </main>
      <footer>공연 정보 출처: 공연예술통합전산망(KOPIS)</footer>
      <BottomNav />
    </>
  )
}
