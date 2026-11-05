import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Button, Card, Text, makeStyles, tokens } from "@fluentui/react-components";
import { ArrowDownloadRegular, DocumentEditRegular, OpenRegular } from "@fluentui/react-icons";
import { officeRoute, useOffice } from "../components/office";
import { api, contentUrl, routeFor } from "../api/client";
import type { Crumb, LibrarySettings, NodeDto } from "../api/types";
import { Crumbs } from "../components/Crumbs";
import { FileIcon } from "../components/FileIcon";
import { useMe } from "../components/me";
import { ItemActions } from "../components/ItemActions";
import { valueText } from "../components/columns";
import { formatDate, formatSize } from "../components/format";

const useStyles = makeStyles({
  card: { marginTop: "12px", maxWidth: "720px" },
  row: { display: "flex", alignItems: "center", gap: "12px" },
  grid: { display: "grid", gridTemplateColumns: "140px 1fr", rowGap: "6px", columnGap: "12px" },
  label: { color: tokens.colorNeutralForeground3 },
});

/** Landing page for a direct link to a file (e.g. a migrated SharePoint URL). */
export function FileView({ file, breadcrumbs }: { file: NodeDto; breadcrumbs: Crumb[] }) {
  const s = useStyles();
  const navigate = useNavigate();
  const library = breadcrumbs[1];
  const settings = useQuery({
    queryKey: ["settings", library.id],
    queryFn: () => api<LibrarySettings>(`/api/v1/nodes/${library.id}/settings`),
  });
  const parent = breadcrumbs[breadcrumbs.length - 2];
  const office = useOffice();
  const me = useMe();

  // puts the file in "Recent"
  useEffect(() => {
    if (!me.anonymous) void api(`/api/v1/nodes/${file.id}/viewed`, { method: "POST" }).catch(() => undefined);
  }, [file.id, me.anonymous]);

  return (
    <>
      <Crumbs items={breadcrumbs} />
      <Card className={s.card}>
        <div className={s.row}>
          <FileIcon kind="FILE" name={file.name} />
          <Text size={500} weight="semibold" style={{ flex: 1, wordBreak: "break-all" }}>
            {file.name}
          </Text>
          <ItemActions
            node={file}
            libraryId={library.id}
            minorVersions={settings.data?.minorVersionsEnabled ?? false}
            onDeleted={() => navigate(routeFor(parent.path))}
          />
        </div>
        <div className={s.grid}>
          <Text className={s.label}>Version</Text>
          <Text>{file.version}</Text>
          <Text className={s.label}>Size</Text>
          <Text>{formatSize(file.sizeBytes)}</Text>
          <Text className={s.label}>Modified</Text>
          <Text>
            {formatDate(file.modifiedAt)} by {file.modifiedBy?.displayName}
          </Text>
          <Text className={s.label}>Created</Text>
          <Text>
            {formatDate(file.createdAt)} by {file.createdBy?.displayName}
          </Text>
          {file.checkedOutBy && (
            <>
              <Text className={s.label}>Checked out to</Text>
              <Text>{file.checkedOutBy.displayName}</Text>
            </>
          )}
          {Object.entries(file.metadata ?? {}).map(([k, v]) => (
            <Fragment2 key={k} k={k} v={v} labelClass={s.label} />
          ))}
        </div>
        <div className={s.row}>
          {office.canOpen(file.name) && !me.anonymous && (
            <Button appearance="primary" icon={<DocumentEditRegular />} onClick={() => navigate(officeRoute(file.id))}>
              Open in browser
            </Button>
          )}
          <Button
            appearance={office.canOpen(file.name) && !me.anonymous ? "secondary" : "primary"}
            icon={<OpenRegular />}
            as="a"
            href={contentUrl(file.id)}
            target="_blank"
          >
            Open file
          </Button>
          <Button icon={<ArrowDownloadRegular />} as="a" href={contentUrl(file.id, { download: true })}>
            Download
          </Button>
        </div>
      </Card>
    </>
  );
}

function Fragment2({ k, v, labelClass }: { k: string; v: unknown; labelClass: string }) {
  return (
    <>
      <Text className={labelClass}>{k}</Text>
      <Text>{valueText(v)}</Text>
    </>
  );
}
