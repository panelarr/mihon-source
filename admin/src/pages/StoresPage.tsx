// SPDX-License-Identifier: GPL-3.0-or-later

import { queryOptions, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { type FormEvent, type ReactNode, useMemo, useState } from "react";
import { api, unwrap } from "../api/client";
import { storeIconPath } from "../api/extras";
import type { AvailableExtension, Store } from "../api/types";
import { AuthIcon } from "../components/AuthIcon";
import { ConfirmDialog } from "../components/Dialog";
import { TabList, tabProps } from "../components/Tabs";
import { Badge, Empty, ErrorBox, Field, Loading, Mono, PageHeader, SearchInput } from "../components/ui";
import { enumLabel, formatDate, languageLabel, shortHash } from "../lib/format";
import { ContentWarningBadge } from "./ExtensionsPage";

const route = getRouteApi("/_app/stores");

const storesQuery = () =>
  queryOptions({
    queryKey: ["stores"],
    queryFn: async () => (await unwrap(api.GET("/v1/admin/stores"))).stores ?? [],
  });

type AvailableFilter = { query?: string; lang?: string; storeId?: string; updatesOnly?: boolean };

const availableQuery = (filter: AvailableFilter) =>
  queryOptions({
    queryKey: ["available", filter],
    queryFn: async () =>
      (await unwrap(api.GET("/v1/admin/stores/available", { params: { query: filter } }))).extensions ?? [],
  });

export function StoresPage() {
  const { view } = route.useSearch();
  return (
    <>
      <PageHeader title="Extension stores" />
      <TabList label="Store views">
        <Link to="/stores" search={{}} {...tabProps(!view)}>
          Stores
        </Link>
        <Link to="/stores" search={{ view: "available" }} {...tabProps(view === "available")}>
          Available extensions
        </Link>
      </TabList>
      <section className="tab-panel">{view === "available" ? <AvailableView /> : <StoresView />}</section>
    </>
  );
}

function StoresView() {
  const stores = useQuery(storesQuery());
  const [removing, setRemoving] = useState<Store | null>(null);
  return (
    <>
      <AddStoreForm />
      <h2>Stores</h2>
      {stores.isPending ? <Loading /> : null}
      <ErrorBox error={stores.error} onRetry={() => stores.refetch()} />
      {stores.data?.length === 0 ? (
        <Empty>No stores. mihon-source comes with none: add the stores you trust above.</Empty>
      ) : null}
      <div className="cards">
        {stores.data?.map((store) => (
          <StoreCard key={store.id} store={store} onRemove={() => setRemoving(store)} />
        ))}
      </div>
      {removing ? <RemoveStoreDialog store={removing} onClose={() => setRemoving(null)} /> : null}
    </>
  );
}

function AddStoreForm() {
  const queryClient = useQueryClient();
  const [url, setUrl] = useState("");
  const [fingerprint, setFingerprint] = useState("");
  const add = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/v1/admin/stores", {
          body: {
            url: url.trim(),
            ...(fingerprint.trim() ? { expectedSigningKeyFingerprint: fingerprint.trim().toLowerCase() } : {}),
          },
        }),
      ),
    onSuccess: async () => {
      setUrl("");
      setFingerprint("");
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["stores"] }),
        queryClient.invalidateQueries({ queryKey: ["available"] }),
      ]);
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    add.mutate();
  };
  return (
    <form className="card form-grid" onSubmit={submit}>
      <h2>Add a store</h2>
      <Field label="Store URL" hint="The store's repo.json, or a legacy index.min.json.">
        <input
          type="url"
          required
          value={url}
          placeholder="https://example.com/repo.json"
          onChange={(event) => setUrl(event.target.value)}
        />
      </Field>
      <Field
        label="Expected signing-key fingerprint (optional)"
        hint="When set, the store's announced key must match. The key is pinned either way."
      >
        <input
          type="text"
          className="mono"
          value={fingerprint}
          onChange={(event) => setFingerprint(event.target.value)}
        />
      </Field>
      <ErrorBox error={add.error} title="Adding the store failed" />
      {add.data?.store ? (
        <p className="success" role="status">
          Added {add.data.store.name || add.data.store.url} with {add.data.store.extensionCount ?? 0} extensions.
        </p>
      ) : null}
      <div className="actions">
        <button type="submit" className="primary" disabled={add.isPending}>
          {add.isPending ? "Adding…" : "Add store"}
        </button>
      </div>
    </form>
  );
}

function StoreCard({ store, onRemove }: { store: Store; onRemove: () => void }) {
  const queryClient = useQueryClient();
  const storeId = store.id ?? "";
  const refresh = useMutation({
    mutationFn: () =>
      unwrap(api.POST("/v1/admin/stores/{storeId}/refresh", { params: { path: { storeId } }, body: {} })),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ["stores"] }),
        queryClient.invalidateQueries({ queryKey: ["available"] }),
      ]),
  });
  return (
    <article className="card">
      <header className="card-header">
        <div>
          <h3>
            {store.name || store.url} {store.badgeLabel ? <Badge tone="info">{store.badgeLabel}</Badge> : null}
          </h3>
          <span className="muted small wrap">{store.url}</span>
        </div>
        {store.lastError ? <Badge tone="bad">Refresh failed</Badge> : null}
      </header>
      {store.lastError ? <pre className="load-error">{store.lastError}</pre> : null}
      <dl className="details compact">
        <dt>ID</dt>
        <dd>
          <Mono>{storeId}</Mono>
        </dd>
        <dt>Format</dt>
        <dd>{enumLabel(store.format, "STORE_FORMAT_")}</dd>
        <dt>Signing key</dt>
        <dd>
          {store.signingKeyFingerprint ? (
            <Mono title={store.signingKeyFingerprint}>{shortHash(store.signingKeyFingerprint)}</Mono>
          ) : (
            <span className="muted">none announced (legacy index)</span>
          )}
        </dd>
        <dt>Contact</dt>
        <dd className="wrap">{store.contact || "—"}</dd>
        <dt>Extensions</dt>
        <dd>
          <Link to="/stores" search={{ view: "available", store: storeId }}>
            {store.extensionCount ?? 0}
          </Link>
        </dd>
        <dt>Added</dt>
        <dd>{formatDate(store.addedAt)}</dd>
        <dt>Refreshed</dt>
        <dd>{formatDate(store.refreshedAt)}</dd>
      </dl>
      <ErrorBox error={refresh.error} title="Refresh" />
      <div className="actions">
        <button type="button" onClick={() => refresh.mutate()} disabled={refresh.isPending}>
          {refresh.isPending ? "Refreshing…" : "Refresh"}
        </button>
        <button type="button" className="danger" onClick={onRemove}>
          Remove
        </button>
      </div>
    </article>
  );
}

function RemoveStoreDialog({ store, onClose }: { store: Store; onClose: () => void }) {
  const queryClient = useQueryClient();
  const remove = useMutation({
    mutationFn: () =>
      unwrap(api.DELETE("/v1/admin/stores/{storeId}", { params: { path: { storeId: store.id ?? "" } } })),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["stores"] }),
        queryClient.invalidateQueries({ queryKey: ["available"] }),
      ]);
      onClose();
    },
  });
  return (
    <ConfirmDialog
      title={`Remove ${store.name || store.url}?`}
      confirmLabel="Remove store"
      pending={remove.isPending}
      error={remove.error}
      onConfirm={() => remove.mutate()}
      onClose={onClose}
    >
      <p>Extensions already installed from it stay installed.</p>
    </ConfirmDialog>
  );
}

function AvailableView() {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const filter: AvailableFilter = {
    ...(search.q ? { query: search.q } : {}),
    ...(search.lang ? { lang: search.lang } : {}),
    ...(search.store ? { storeId: search.store } : {}),
    ...(search.updates ? { updatesOnly: true } : {}),
  };
  const available = useQuery(availableQuery(filter));
  // The unfiltered list gives the language and store choices.
  const all = useQuery(availableQuery({}));
  const stores = useQuery(storesQuery());

  const languages = useMemo(
    () =>
      [...new Set((all.data ?? []).flatMap((ext) => (ext.sources ?? []).map((s) => s.lang ?? "")))]
        .filter(Boolean)
        .sort(),
    [all.data],
  );
  const storesById = useMemo(() => new Map((stores.data ?? []).map((store) => [store.id ?? "", store])), [stores.data]);

  const setSearch = (patch: { q?: string; lang?: string; store?: string; updates?: boolean }) =>
    navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true });

  const sorted = sortAvailable(available.data ?? []);

  return (
    <>
      <div className="toolbar">
        <SearchInput
          label="Search"
          value={search.q ?? ""}
          placeholder="Extension or source name"
          onChange={(q) => setSearch({ q: q || undefined })}
        />
        <Field label="Language">
          <select value={search.lang ?? ""} onChange={(event) => setSearch({ lang: event.target.value || undefined })}>
            <option value="">All languages</option>
            {languages.map((lang) => (
              <option key={lang} value={lang}>
                {languageLabel(lang)}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Store">
          <select
            value={search.store ?? ""}
            onChange={(event) => setSearch({ store: event.target.value || undefined })}
          >
            <option value="">All stores</option>
            {(stores.data ?? []).map((store) => (
              <option key={store.id} value={store.id}>
                {store.name || store.url}
              </option>
            ))}
          </select>
        </Field>
        <label className="filter-check toolbar-check">
          <input
            type="checkbox"
            checked={search.updates ?? false}
            onChange={(event) => setSearch({ updates: event.target.checked || undefined })}
          />
          <span>Updates only</span>
        </label>
      </div>

      {available.isPending ? <Loading /> : null}
      <ErrorBox error={available.error} onRetry={() => available.refetch()} />
      {available.data?.length === 0 ? (
        <Empty>
          {Object.keys(filter).length > 0 ? "No extension matches." : "No extensions available. Add a store first."}
        </Empty>
      ) : null}
      {sorted.length > 0 ? (
        <table className="table">
          <thead>
            <tr>
              <th>Extension</th>
              <th>Version</th>
              <th>Languages</th>
              <th>Store</th>
              <th>State</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {sorted.map((ext) => (
              <AvailableRow key={`${ext.storeId}/${ext.pkgName}`} ext={ext} store={storesById.get(ext.storeId ?? "")} />
            ))}
          </tbody>
        </table>
      ) : null}
    </>
  );
}

/**
 * Orders extensions with updates first, then installed ones, then by name. Array sort is stable, so equal
 * entries keep the server's order. The filters only narrow the list, so every filter shows the same order.
 */
export function sortAvailable(extensions: readonly AvailableExtension[]): AvailableExtension[] {
  return [...extensions].sort(
    (a, b) =>
      Number(b.updateAvailable ?? false) - Number(a.updateAvailable ?? false) ||
      Number(b.installed ?? false) - Number(a.installed ?? false) ||
      (a.name ?? "").localeCompare(b.name ?? ""),
  );
}

/** Whether the extension is installed, and how its installed version compares. */
function AvailableState({ ext }: { ext: AvailableExtension }) {
  if (ext.updateAvailable) {
    return (
      <>
        <Badge tone="info">Update available</Badge>
        <div className="muted small">
          {ext.installedVersionName} → {ext.versionName}
        </div>
      </>
    );
  }
  if (ext.installed) {
    const other = ext.installedVersionName && ext.installedVersionName !== ext.versionName;
    return (
      <>
        <Badge tone="good">Installed</Badge>
        {other ? <div className="muted small">version {ext.installedVersionName}</div> : null}
      </>
    );
  }
  if (!ext.supported) return <Badge tone="bad">Unsupported</Badge>;
  return <span className="muted">Not installed</span>;
}

function AvailableRow({ ext, store }: { ext: AvailableExtension; store: Store | undefined }) {
  const queryClient = useQueryClient();
  const install = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/v1/admin/stores/{storeId}/extensions/{pkgName}/install", {
          params: { path: { storeId: ext.storeId ?? "", pkgName: ext.pkgName ?? "" } },
          body: {},
        }),
      ),
    onSuccess: () =>
      Promise.all(
        [["available"], ["extensions"], ["sources"], ["assignments"]].map((queryKey) =>
          queryClient.invalidateQueries({ queryKey }),
        ),
      ),
  });
  const langs = [...new Set((ext.sources ?? []).map((source) => source.lang ?? ""))].filter(Boolean);

  let action: ReactNode;
  if (!ext.supported) action = <span className="muted small">Unsupported lib version</span>;
  else if (ext.updateAvailable)
    action = (
      <button type="button" className="primary small" onClick={() => install.mutate()} disabled={install.isPending}>
        {install.isPending ? "Updating…" : "Update"}
      </button>
    );
  else if (ext.installed) action = null;
  else
    action = (
      <button type="button" className="small" onClick={() => install.mutate()} disabled={install.isPending}>
        {install.isPending ? "Installing…" : "Install"}
      </button>
    );

  return (
    <tr>
      <td>
        <div className="with-icon">
          <AuthIcon
            path={ext.storeId && ext.pkgName && ext.iconUrl ? storeIconPath(ext.storeId, ext.pkgName) : undefined}
            label={ext.name || ext.pkgName || ""}
          />
          <div>
            <strong>{ext.name || ext.pkgName}</strong> <ContentWarningBadge warning={ext.contentWarning} />
            <br />
            <Mono>{ext.pkgName}</Mono>
          </div>
        </div>
        <ErrorBox error={install.error} />
      </td>
      <td>
        {ext.versionName}
        <div className="muted small">
          lib {ext.libVersion || "?"}
          {ext.hasJar ? <span title="The store offers a jar: no APK conversion needed"> · jar</span> : " · apk"}
        </div>
      </td>
      <td className="small">{langs.join(", ") || "—"}</td>
      <td>
        {store?.badgeLabel ? (
          <Badge tone="info">{store.badgeLabel}</Badge>
        ) : (
          <span className="small">{store?.name ?? ext.storeId}</span>
        )}
      </td>
      <td>
        <AvailableState ext={ext} />
      </td>
      <td>{action}</td>
    </tr>
  );
}
