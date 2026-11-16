import { Link, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import AdminPage from './pages/AdminPage'
import LoginPage from './pages/LoginPage'
import PerformanceDetailPage from './pages/PerformanceDetailPage'
import PerformanceListPage from './pages/PerformanceListPage'
import SignUpPage from './pages/SignUpPage'

export default function App() {
  const { user, logout } = useAuth()
  return (
    <>
      <header className="top">
        <Link to="/" className="brand">curtaincall</Link>
        <nav>
          {user ? (
            <>
              {user.role === 'ADMIN' && <Link to="/admin">관리</Link>}
              <span>{user.name}</span>
              <button onClick={() => void logout()}>로그아웃</button>
            </>
          ) : (
            <>
              <Link to="/login">로그인</Link>
              <Link to="/signup">가입</Link>
            </>
          )}
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<PerformanceListPage />} />
          <Route path="/performances/:id" element={<PerformanceDetailPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignUpPage />} />
          <Route path="*" element={<p>페이지를 찾을 수 없습니다.</p>} />
        </Routes>
      </main>
      <footer>공연 정보 출처: 공연예술통합전산망(KOPIS)</footer>
    </>
  )
}
