import { useState } from "react";

const navigation = [
  { id: "overview", label: "Overview", icon: "◫" },
  { id: "jobs", label: "SMS jobs", icon: "▤" },
  { id: "gateways", label: "Gateways", icon: "▦" },
  { id: "activity", label: "Activity", icon: "◷" },
  { id: "settings", label: "Settings", icon: "⚙" }
];

function StatusPill({ children, tone = "muted" }) {
  return <span className={`status-pill ${tone}`}><i />{children}</span>;
}

function EmptyState({ title, detail }) {
  return (
    <div className="empty-state">
      <div className="empty-icon">⌁</div>
      <strong>{title}</strong>
      <p>{detail}</p>
    </div>
  );
}

function Overview() {
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">CONTROL CENTRE</div>
          <h1>Gateway overview</h1>
          <p>Monitor your registered SMS gateway phones and controller activity.</p>
        </div>
        <StatusPill tone="warning">Controller not connected</StatusPill>
      </div>

      <div className="notice">
        <div className="notice-mark">!</div>
        <div>
          <strong>Waiting for backend connection</strong>
          <p>This console is running in preview mode. Live gateway status, job queues and controls will appear after the secure controller endpoint is configured.</p>
        </div>
      </div>

      <section className="metric-grid">
        <article className="metric-card">
          <div className="metric-label">Registered gateways</div>
          <div className="metric-value">—</div>
          <div className="metric-foot">Awaiting controller data</div>
        </article>
        <article className="metric-card">
          <div className="metric-label">Jobs in queue</div>
          <div className="metric-value">—</div>
          <div className="metric-foot">No live queue connection</div>
        </article>
        <article className="metric-card">
          <div className="metric-label">Active SMS attempt</div>
          <div className="metric-value">—</div>
          <div className="metric-foot">No attempt has been loaded</div>
        </article>
        <article className="metric-card">
          <div className="metric-label">Dispatch safety</div>
          <div className="metric-value safety-value">Locked</div>
          <div className="metric-foot">Sending remains disabled</div>
        </article>
      </section>

      <div className="content-grid">
        <section className="panel">
          <div className="panel-heading">
            <div><h2>Gateway phones</h2><p>Phone-based SMS workers</p></div>
            <span className="subtle-count">0 connected</span>
          </div>
          <EmptyState title="No gateway data yet" detail="Registered Android gateway phones will be listed here after the controller is connected." />
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div><h2>Current operation</h2><p>Active 3030 transaction</p></div>
            <StatusPill>Idle</StatusPill>
          </div>
          <div className="operation-empty">
            <div className="operation-symbol">↗</div>
            <strong>No active attempt</strong>
            <p>There is no live SMS attempt to monitor or cancel.</p>
            <button className="button button-disabled" disabled>Cancel active job</button>
          </div>
        </section>
      </div>

      <section className="panel safety-panel">
        <div className="safety-icon">盾</div>
        <div className="safety-copy">
          <strong>SMS transmission is protected</strong>
          <p>The four-minute cancellation cooldown is enforced by the controller after a confirmed sent SMS is cancelled. The next query is blocked until that exact deadline. No post-reply waiting period is added.</p>
        </div>
        <StatusPill tone="success">Safety rules retained</StatusPill>
      </section>
    </>
  );
}

function SectionPage({ section }) {
  const copy = {
    jobs: ["SMS jobs", "Review queued, active, completed and quarantined 3030 checks.", "Job records will load when the controller is connected."],
    gateways: ["Gateway devices", "Manage registered Android phones and their readiness.", "No gateway registrations are available in preview mode."],
    activity: ["Activity log", "Review controller events, attempt transitions and safety decisions.", "The activity stream will appear after secure connection."],
    settings: ["Controller settings", "Configure the secure connection between this console and the Gateway controller.", "The controller endpoint has not yet been provisioned."]
  }[section];
  return (
    <div className="page-heading">
      <div>
        <div className="eyebrow">DEVMonitor GATEWAY</div>
        <h1>{copy[0]}</h1>
        <p>{copy[1]}</p>
      </div>
      <StatusPill tone="warning">Preview only</StatusPill>
      <div className="section-placeholder panel">
        <EmptyState title={copy[2]} detail="No sample or fabricated live data is shown." />
      </div>
    </div>
  );
}

export default function App() {
  const [active, setActive] = useState("overview");
  const selected = navigation.find(item => item.id === active);
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">D</div>
          <div><strong>DevMonitor</strong><span>GATEWAY 5.0</span></div>
        </div>
        <div className="workspace-label">WORKSPACE</div>
        <nav aria-label="Main navigation">
          {navigation.map(item => (
            <button key={item.id} className={active === item.id ? "nav-item active" : "nav-item"} onClick={() => setActive(item.id)}>
              <span className="nav-icon">{item.icon}</span>{item.label}
              {item.id === "jobs" && <span className="nav-count">—</span>}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="connection-dot" />
          <div><strong>Offline preview</strong><span>Controller disconnected</span></div>
        </div>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <div className="breadcrumb">Gateway <span>/</span> {selected.label}</div>
          <div className="topbar-right"><span className="preview-tag">PREVIEW MODE</span><div className="avatar">G</div></div>
        </header>
        <div className="page-content">
          {active === "overview" ? <Overview /> : <SectionPage section={active} />}
          <footer>DevMonitor Gateway 5.0 <span>•</span> Secure controller connection required for live operations</footer>
        </div>
      </main>
    </div>
  );
}
