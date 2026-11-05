import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Text, makeStyles, tokens } from "@fluentui/react-components";
import { ArrowUploadRegular, DeleteRegular, HistoryRegular } from "@fluentui/react-icons";
import { api, contentUrl, routeFor, uploadFiles } from "../api/client";
import type { ColumnDto, ItemVersion, NodeDto } from "../api/types";
import { FileIcon } from "../components/FileIcon";
import { can } from "../components/me";
import { valueText } from "../components/columns";
import { ErrorBar } from "../components/States";
import { formatDate, formatSize } from "../components/format";

const useStyles = makeStyles({
  section: { display: "flex", flexDirection: "column", gap: "4px", paddingTop: "8px", borderTop: `1px solid ${tokens.colorNeutralStroke2}` },
  head: { display: "flex", alignItems: "center", justifyContent: "space-between", gap: "8px" },
  row: { display: "flex", alignItems: "center", gap: "8px", padding: "4px 0" },
  grow: { flex: 1, minWidth: 0, overflowWrap: "anywhere" },
  muted: { color: tokens.colorNeutralForeground3 },
  version: { padding: "6px 0", borderTop: `1px solid ${tokens.colorNeutralStroke3}` },
});

/** Attachments and version history of a list item. */
export function ItemExtras(props: { item: NodeDto; columns: ColumnDto[]; onRestored: () => void }) {
  const s = useStyles();
  const { item, columns } = props;
  const qc = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<unknown>(null);
  const [history, setHistory] = useState(false);
  const attachments = useQuery({ queryKey: ["children", item.id], queryFn: () => api<NodeDto[]>(`/api/v1/nodes/${item.id}/children`) });
  const versions = useQuery({
    queryKey: ["item-versions", item.id], enabled: history,
    queryFn: () => api<ItemVersion[]>(`/api/v1/nodes/${item.id}/item-versions`),
  });

  const run = async (fn: () => Promise<unknown>) => {
    setError(null);
    try {
      await fn();
      await qc.invalidateQueries({ queryKey: ["children", item.id] });
      await qc.invalidateQueries({ queryKey: ["covers"] });
    } catch (e) {
      setError(e);
    }
  };

  const restore = async (version: number) => {
    setError(null);
    try {
      await api(`/api/v1/nodes/${item.id}/item-versions/${version}/restore`, { method: "POST" });
      await qc.invalidateQueries();
      props.onRestored();
    } catch (e) {
      setError(e);
    }
  };

  /** What changed compared with the version before. */
  const changes = (v: ItemVersion, older?: ItemVersion) => {
    if (!older) return "Created";
    const out: string[] = [];
    if (v.title !== older.title) out.push(`Title: ${v.title}`);
    for (const c of columns) {
      const now = valueText(v.values?.[c.internalName]);
      if (now !== valueText(older.values?.[c.internalName])) out.push(`${c.displayName}: ${now || "(empty)"}`);
    }
    return out.join(" · ") || "No visible change";
  };

  return (
    <>
      <ErrorBar error={error} />
      <div className={s.section}>
        <div className={s.head}>
          <Text weight="semibold">Attachments</Text>
          {can(item, "EDIT_ITEMS") && (
            <Button size="small" icon={<ArrowUploadRegular />} onClick={() => input.current?.click()}>Attach</Button>
          )}
        </div>
        {!attachments.data?.length && <Text size={200} className={s.muted}>No attachments.</Text>}
        {attachments.data?.map((f) => (
          <div key={f.id} className={s.row}>
            <FileIcon kind="FILE" name={f.name} />
            <a className={s.grow} href={contentUrl(f.id)} target="_blank" rel="noopener noreferrer">{f.name}</a>
            <Text size={200} className={s.muted}>{formatSize(f.sizeBytes)}</Text>
            {can(item, "EDIT_ITEMS") && (
              <Button appearance="subtle" size="small" icon={<DeleteRegular />} aria-label={`Remove ${f.name}`}
                onClick={() => run(() => api(`/api/v1/nodes/${f.id}`, { method: "DELETE" }))} />
            )}
          </div>
        ))}
        <input ref={input} type="file" multiple hidden onChange={(e) => {
          const files = Array.from(e.target.files ?? []);
          e.target.value = "";
          if (files.length) void run(() => uploadFiles(item.id, files, false));
        }} />
      </div>
      <div className={s.section}>
        <div className={s.head}>
          <Text weight="semibold">Version history</Text>
          <Button size="small" appearance="subtle" icon={<HistoryRegular />} onClick={() => setHistory((h) => !h)}>{history ? "Hide" : "Show"}</Button>
        </div>
        {history && versions.data?.map((v, i) => (
          <div key={v.version} className={s.version}>
            <div className={s.row}>
              <Text weight="semibold">{v.version}.0</Text>
              <Text size={200} className={`${s.grow} ${s.muted}`}>{formatDate(v.createdAt)} by {v.createdBy?.displayName}</Text>
              {v.current ? <Text size={200} className={s.muted}>Current</Text>
                : can(item, "EDIT_ITEMS") && <Button size="small" onClick={() => restore(v.version)}>Restore</Button>}
            </div>
            <Text size={200}>{changes(v, versions.data?.[i + 1])}</Text>
          </div>
        ))}
      </div>
      <Text size={200} className={s.muted}>Link: <a href={routeFor(item.path)}>{item.path}</a></Text>
    </>
  );
}
