// SPDX-License-Identifier: GPL-3.0-or-later

import { queryOptions, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { api, unwrap } from "../api/client";
import { uploadExtension } from "../api/extras";
import type { ContentWarning, Extension } from "../api/types";
import { Dialog } from "../components/Dialog";
import { Badge, Empty, ErrorBox, Field, Loading, Mono, PageHeader } from "../components/ui";
import { enumLabel, formatDate, languageLabel, shortHash } from "../lib/format";

export const extensionsQuery = () =>
  queryOptions({
    queryKey: ["extensions"],
    queryFn: async () => (await unwrap(api.GET("/v1/admin/extensions"))).extensions ?? [],
  });

export function ContentWarningBadge({ warning }: { warning: ContentWarning | undefined }) {
  if (warning === "CONTENT_WARNING_NSFW") return <Badge tone="bad">NSFW</Badge>;
  if (warning === "CONTENT_WARNING_MIXED") return <Badge tone="warn">Mixed content</Badge>;
  return null;
}

/** Installing or removing extensions changes the sources and the stores' install state too. */
function useInvalidateExtensions() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all(
      [["extensions"], ["sources"], ["available"], ["assignments"]].map((queryKey) =>
        queryClient.invalidateQueries({ queryKey }),
      ),
    );
}

export function ExtensionsPage() {
  const extensions = useQuery(extensionsQuery());
  const [uninstalling, setUninstalling] = useState<Extension | null>(null);

  const sorted = [...(extensions.data ?? [])].sort((a, b) => (a.name ?? "").localeCompare(b.name ?? ""));

  return (
    <>
      <PageHeader title="Extensions" />
      <InstallPanel />

      <h2>Installed</h2>
      {extensions.isPending ? <Loading /> : null}
      <ErrorBox error={extensions.error} onRetry={() => extensions.refetch()} />
      {extensions.data?.length === 0 ? (
        <Empty>
          No extensions installed. Install one above, or from a <Link to="/stores">store</Link>.
        </Empty>
      ) : null}
      <div className="cards">
        {sorted.map((extension) => (
          <ExtensionCard key={extension.pkgName} extension={extension} onUninstall={() => setUninstalling(extension)} />
        ))}
      </div>
      {uninstalling ? <UninstallDialog extension={uninstalling} onClose={() => setUninstalling(null)} /> : null}
    </>
  );
}

function ExtensionCard({ extension, onUninstall }: { extension: Extension; onUninstall: () => void }) {
  const invalidate = useInvalidateExtensions();
  const pkgName = extension.pkgName ?? "";
  const reload = useMutation({
    mutationFn: () =>
      unwrap(api.POST("/v1/admin/extensions/{pkgName}/reload", { params: { path: { pkgName } }, body: {} })),
    onSuccess: invalidate,
  });
  const failed = extension.status === "EXTENSION_STATUS_ERROR";
  const origin = extension.origin;

  return (
    <article className={failed ? "card extension failed" : "card extension"}>
      <header className="card-header">
        <div>
          <h3>{extension.name || pkgName}</h3>
          <Mono>{pkgName}</Mono>
        </div>
        <span className="badges">
          {failed ? <Badge tone="bad">Error</Badge> : <Badge tone="good">Loaded</Badge>}
          <ContentWarningBadge warning={extension.contentWarning} />
        </span>
      </header>
      {extension.loadError ? <pre className="load-error">{extension.loadError}</pre> : null}
      <dl className="details compact">
        <dt>Version</dt>
        <dd>
          {extension.versionName || "—"} <span className="muted">({extension.versionCode ?? "?"})</span>
        </dd>
        <dt>Lib version</dt>
        <dd>{extension.libVersion || "—"}</dd>
        <dt>Format</dt>
        <dd>{enumLabel(extension.format, "EXTENSION_FORMAT_").toUpperCase()}</dd>
        <dt>Signed by</dt>
        <dd>
          {extension.signingFingerprint ? (
            <Mono title={extension.signingFingerprint}>{shortHash(extension.signingFingerprint)}</Mono>
          ) : (
            <span className="muted">unsigned</span>
          )}
        </dd>
        <dt>SHA-256</dt>
        <dd>
          <Mono title={extension.sha256}>{shortHash(extension.sha256)}</Mono>
        </dd>
        <dt>Origin</dt>
        <dd className="wrap">
          {origin?.url ? (
            <>URL: {origin.url}</>
          ) : origin?.upload ? (
            <>Upload: {origin.upload}</>
          ) : origin?.storeId ? (
            <>
              Store: <Mono>{origin.storeId}</Mono>
            </>
          ) : (
            "—"
          )}
        </dd>
        <dt>Installed</dt>
        <dd>{formatDate(extension.installedAt)}</dd>
      </dl>
      {extension.sources?.length ? (
        <details>
          <summary>
            {extension.sources.length} source{extension.sources.length === 1 ? "" : "s"}
          </summary>
          <ul className="plain-list">
            {extension.sources.map((source) => (
              <li key={source.id}>
                <Link to="/sources/$sourceId" params={{ sourceId: source.id ?? "" }}>
                  {source.name}
                </Link>{" "}
                <span className="muted small">
                  {languageLabel(source.lang)} · <Mono>{source.id}</Mono>
                </span>
              </li>
            ))}
          </ul>
        </details>
      ) : null}
      <ErrorBox error={reload.error} title="Reload" />
      <div className="actions">
        <button type="button" onClick={() => reload.mutate()} disabled={reload.isPending}>
          {reload.isPending ? "Reloading…" : "Reload"}
        </button>
        <button type="button" className="danger" onClick={onUninstall}>
          Uninstall
        </button>
      </div>
    </article>
  );
}

function UninstallDialog({ extension, onClose }: { extension: Extension; onClose: () => void }) {
  const invalidate = useInvalidateExtensions();
  const [deleteData, setDeleteData] = useState(false);
  const pkgName = extension.pkgName ?? "";
  const uninstall = useMutation({
    mutationFn: () =>
      unwrap(api.DELETE("/v1/admin/extensions/{pkgName}", { params: { path: { pkgName }, query: { deleteData } } })),
    onSuccess: async () => {
      await invalidate();
      onClose();
    },
  });

  return (
    <Dialog title={`Uninstall ${extension.name || pkgName}?`} onClose={onClose}>
      <p>The extension and its sources are removed.</p>
      <label className="filter-check">
        <input type="checkbox" checked={deleteData} onChange={(event) => setDeleteData(event.target.checked)} />
        <span>Also delete the sources' settings and cookies</span>
      </label>
      <ErrorBox error={uninstall.error} />
      <div className="actions">
        <button type="button" className="danger" onClick={() => uninstall.mutate()} disabled={uninstall.isPending}>
          {uninstall.isPending ? "Uninstalling…" : "Uninstall"}
        </button>
        <button type="button" onClick={onClose}>
          Cancel
        </button>
      </div>
    </Dialog>
  );
}

function InstalledNote({ extension }: { extension: Extension | undefined }) {
  if (!extension) return null;
  const failed = extension.status === "EXTENSION_STATUS_ERROR";
  return (
    <p className={failed ? "notice" : "success"} role="status">
      Installed {extension.name || extension.pkgName} {extension.versionName}
      {failed ? `, but it failed to load: ${extension.loadError || "unknown error"}` : "."}
    </p>
  );
}

function InstallPanel() {
  const [mode, setMode] = useState<"url" | "upload">("url");
  return (
    <section className="card">
      <header className="card-header">
        <h2>Install</h2>
        <div className="segmented" role="tablist">
          <button type="button" role="tab" aria-selected={mode === "url"} onClick={() => setMode("url")}>
            From URL
          </button>
          <button type="button" role="tab" aria-selected={mode === "upload"} onClick={() => setMode("upload")}>
            Upload a file
          </button>
        </div>
      </header>
      {mode === "url" ? <InstallFromUrl /> : <InstallFromFile />}
    </section>
  );
}

function InstallFromUrl() {
  const invalidate = useInvalidateExtensions();
  const [url, setUrl] = useState("");
  const [sha256, setSha256] = useState("");
  const [fingerprint, setFingerprint] = useState("");
  const install = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/v1/admin/extensions", {
          body: {
            url: url.trim(),
            ...(sha256.trim() ? { expectedSha256: sha256.trim().toLowerCase() } : {}),
            ...(fingerprint.trim() ? { expectedSigningFingerprint: fingerprint.trim().toLowerCase() } : {}),
          },
        }),
      ),
    onSuccess: async () => {
      setUrl("");
      await invalidate();
    },
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    install.mutate();
  };

  return (
    <form className="form-grid" onSubmit={submit}>
      <Field label="URL of a .jar or .apk">
        <input
          type="url"
          required
          value={url}
          placeholder="https://example.com/extension.apk"
          onChange={(event) => setUrl(event.target.value)}
        />
      </Field>
      <Field label="Expected SHA-256 (optional)" hint="Nothing is installed if the file's hash differs.">
        <input type="text" className="mono" value={sha256} onChange={(event) => setSha256(event.target.value)} />
      </Field>
      <Field label="Expected signing fingerprint (optional)" hint="SHA-256 of the signing certificate, hex.">
        <input
          type="text"
          className="mono"
          value={fingerprint}
          onChange={(event) => setFingerprint(event.target.value)}
        />
      </Field>
      <ErrorBox error={install.error} title="Install failed" />
      <InstalledNote extension={install.data?.extension} />
      <div className="actions">
        <button type="submit" className="primary" disabled={install.isPending}>
          {install.isPending ? "Installing…" : "Install"}
        </button>
      </div>
    </form>
  );
}

function InstallFromFile() {
  const invalidate = useInvalidateExtensions();
  const [file, setFile] = useState<File | null>(null);
  const install = useMutation({
    mutationFn: (selected: File) => uploadExtension(selected),
    onSuccess: invalidate,
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (file) install.mutate(file);
  };

  return (
    <form className="form-grid" onSubmit={submit}>
      <Field label="Extension file (.jar or .apk)">
        <input type="file" accept=".jar,.apk" required onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
      </Field>
      <ErrorBox error={install.error} title="Upload failed" />
      <InstalledNote extension={install.data?.extension} />
      <div className="actions">
        <button type="submit" className="primary" disabled={!file || install.isPending}>
          {install.isPending ? "Uploading…" : "Upload and install"}
        </button>
      </div>
    </form>
  );
}
