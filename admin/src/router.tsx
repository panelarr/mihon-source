// SPDX-License-Identifier: GPL-3.0-or-later

import type { QueryClient } from "@tanstack/react-query";
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  type RouterHistory,
  redirect,
} from "@tanstack/react-router";
import { session } from "./auth/session";
import { AppLayout, NotFound, RootLayout } from "./pages/AppLayout";
import { ClientsPage } from "./pages/ClientsPage";
import { ExtensionsPage } from "./pages/ExtensionsPage";
import { IntegrationsPage } from "./pages/IntegrationsPage";
import { LoginPage } from "./pages/LoginPage";
import { StoresPage } from "./pages/StoresPage";
import { ChapterPage } from "./pages/sources/ChapterPage";
import { SeriesPage } from "./pages/sources/SeriesPage";
import { SourcePage, type SourceTab } from "./pages/sources/SourcePage";
import { SourcesPage } from "./pages/sources/SourcesPage";

export type RouterContext = { queryClient: QueryClient };

type Search = Record<string, unknown>;

function text(value: unknown): string | undefined {
  if (typeof value === "string" && value !== "") return value;
  if (typeof value === "number") return String(value);
  return undefined;
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
  notFoundComponent: NotFound,
});

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/login",
  validateSearch: (search: Search): { redirect?: string; expired?: boolean } => ({
    redirect: text(search.redirect),
    expired: search.expired === true || search.expired === "true" ? true : undefined,
  }),
  beforeLoad: () => {
    if (session.signedIn()) throw redirect({ to: "/sources" });
  },
  component: LoginPage,
});

// Every page but the login needs the token.
const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: "_app",
  beforeLoad: ({ location }) => {
    if (!session.signedIn()) throw redirect({ to: "/login", search: { redirect: location.href } });
  },
  component: AppLayout,
});

const indexRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/",
  beforeLoad: () => {
    throw redirect({ to: "/sources" });
  },
});

const sourcesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/sources",
  validateSearch: (search: Search): { q?: string; lang?: string } => ({
    q: text(search.q),
    lang: text(search.lang),
  }),
  component: SourcesPage,
});

const tabs: readonly SourceTab[] = ["popular", "latest", "search", "settings"];

const sourceRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/sources/$sourceId",
  validateSearch: (search: Search): { tab?: SourceTab } => ({
    tab: tabs.find((tab) => tab === search.tab),
  }),
  component: SourcePage,
});

const seriesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/sources/$sourceId/series",
  validateSearch: (search: Search): { key: string } => ({ key: text(search.key) ?? "" }),
  component: SeriesPage,
});

const chapterRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/sources/$sourceId/chapter",
  validateSearch: (search: Search): { key: string; series?: string } => ({
    key: text(search.key) ?? "",
    series: text(search.series),
  }),
  component: ChapterPage,
});

const extensionsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/extensions",
  component: ExtensionsPage,
});

const storesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/stores",
  validateSearch: (
    search: Search,
  ): { view?: "available"; q?: string; lang?: string; store?: string; updates?: boolean } => ({
    view: search.view === "available" ? "available" : undefined,
    q: text(search.q),
    lang: text(search.lang),
    store: text(search.store),
    updates: search.updates === true || search.updates === "true" ? true : undefined,
  }),
  component: StoresPage,
});

const clientsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/clients",
  component: ClientsPage,
});

const integrationsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/integrations",
  component: IntegrationsPage,
});

const routeTree = rootRoute.addChildren([
  loginRoute,
  appRoute.addChildren([
    indexRoute,
    sourcesRoute,
    sourceRoute,
    seriesRoute,
    chapterRoute,
    extensionsRoute,
    storesRoute,
    clientsRoute,
    integrationsRoute,
  ]),
]);

export function createAppRouter(queryClient: QueryClient, history?: RouterHistory) {
  return createRouter({
    routeTree,
    context: { queryClient },
    defaultPreload: false,
    scrollRestoration: true,
    ...(history ? { history } : {}),
  });
}

export type AppRouter = ReturnType<typeof createAppRouter>;

declare module "@tanstack/react-router" {
  interface Register {
    router: AppRouter;
  }
}
