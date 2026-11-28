import { Link, NavLink } from 'react-router-dom'
import { useAuth } from '../auth'

// 1280px 이상은 위 메뉴, 그보다 좁으면 주 메뉴를 하단 메뉴로 옮기고 위에는 계정 동작만 남긴다
export function TopNav() {
  const { user, logout } = useAuth()
  return (
    <header className="top">
      <div className="top-inner">
        <Link to="/" className="brand">curtain<span>call</span></Link>
        <nav aria-label="주 메뉴">
          <NavLink to="/" end className="pc-only">홈</NavLink>
          {user && <NavLink to="/reservations" className="pc-only">예매 내역</NavLink>}
          {user?.role === 'ADMIN' && <NavLink to="/admin">관리</NavLink>}
          {user ? (
            <>
              <span className="nav-account">{user.name}</span>
              <button onClick={() => void logout()}>로그아웃</button>
            </>
          ) : (
            <>
              <NavLink to="/login" className="nav-account">로그인</NavLink>
              <NavLink to="/signup">가입</NavLink>
            </>
          )}
        </nav>
      </div>
    </header>
  )
}

export function BottomNav() {
  return (
    <nav aria-label="하단 메뉴" className="bottom-nav">
      <NavLink to="/" end>홈</NavLink>
      <NavLink to="/reservations">예매 내역</NavLink>
    </nav>
  )
}
