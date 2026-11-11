import { Link } from 'react-router-dom'
import { Icon } from '../components/Icon'
import { Logo } from '../components/Logo'

export function Landing() {
  return (
    <div className="center-page">
      <div className="landing">
        <div className="hero"><Logo large /><h1 style={{ margin: 0 }}>ZSCMST Smart Library</h1></div>
        <p className="muted">Learning Resource Center · Zamboanga State College of Marine Sciences and Technology</p>
        <div className="tiles">
          <Link className="card tile" to="/opac">
            <Icon name="search" className="tile-icon" /><strong>Search the catalog</strong>
            <span className="muted small">Find books, theses and media and see if they are on the shelf.</span>
          </Link>
          <Link className="card tile" to="/my">
            <Icon name="user" className="tile-icon" /><strong>My library account</strong>
            <span className="muted small">View loans, renew items, reserve titles and check fines.</span>
          </Link>
          <Link className="card tile" to="/staff">
            <Icon name="briefcase" className="tile-icon" /><strong>Staff</strong>
            <span className="muted small">Circulation, cataloging, patrons, reports and administration.</span>
          </Link>
        </div>
      </div>
    </div>
  )
}
