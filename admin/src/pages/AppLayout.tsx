// SPDX-License-Identifier: GPL-3.0-or-later

import { useQuery } from "@tanstack/react-query";
import { Link, Outlet } from "@tanstack/react-router";
import { api, unwrap } from "../api/client";
import { session } from "../auth/session";
import { BrandIcon, Wordmark } from "../components/Brand";

export function RootLayout() {
  return <Outlet />;
}

const nav = [
  { to: "/sources", label: "Sources" },
  { to: "/extensions", label: "Extensions" },
  { to: "/stores", label: "Stores" },
  { to: "/clients", label: "Clients" },
  { to: "/integrations", label: "Integrations" },
] as const;

export function AppLayout() {
  const info = useQuery({
    queryKey: ["info"],
    queryFn: () => unwrap(api.GET("/v1/info")),
    staleTime: Number.POSITIVE_INFINITY,
  });
  const health = useQuery({
    queryKey: ["health"],
    queryFn: () => unwrap(api.GET("/v1/health")),
    refetchInterval: 60_000,
  });
  const status = health.data?.status;

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/sources" className="brand">
          <BrandIcon size={26} />
          <Wordmark />
        </Link>
        <nav className="nav" aria-label="Main">
          {nav.map((item) => (
            <Link key={item.to} to={item.to} activeProps={{ className: "active" }}>
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="topbar-end">
          {info.data?.info?.version ? <span className="muted small">v{info.data.info.version}</span> : null}
          {status && status !== "HEALTH_STATUS_SERVING" ? (
            <span className="badge badge-warn" title={health.data?.message}>
              {status === "HEALTH_STATUS_DEGRADED" ? "Degraded" : "Not serving"}
            </span>
          ) : null}
          <button type="button" className="small" onClick={() => session.signOut("logout")}>
            Log out
          </button>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}

export function NotFound() {
  return (
    <main className="content">
      <h1>Not found</h1>
      <p>
        There's no page here. <Link to="/sources">Go to the sources</Link>.
      </p>
    </main>
  );
}
