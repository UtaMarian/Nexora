import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Badge,
  Card,
  Menu,
  MenuDivider,
  MenuGroupHeader,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  MessageBar,
  MessageBarBody,
  ProgressBar,
  Table,
  TableBody,
  TableCell,
  TableCellLayout,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
  Toolbar,
  ToolbarButton,
  Tooltip,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import {
  AddRegular,
  AlertRegular,
  ArrowUploadRegular,
  FlashRegular,
  DocumentAddRegular,
  DocumentEditRegular,
  DocumentTextRegular,
  FolderAddRegular,
  HardDriveRegular,
  LockClosedRegular,
  PeopleRegular,
  SettingsRegular,
  SlideTextRegular,
  TableRegular,
} from "@fluentui/react-icons";
import { api, ApiError, contentUrl, routeFor, uploadFiles } from "../api/client";
import type { ColumnDto, Crumb, LibrarySettings, NodeDto } from "../api/types";
import { Crumbs } from "../components/Crumbs";
import { ColumnValue } from "../components/columns";
import { RulesDialog } from "../workflow/RulesDialog";
import { approvalBadge } from "../workflow/types";
import { AlertDialog } from "../dialogs/AlertDialog";
import { NewDocumentDialog, type NewDocumentKind } from "../dialogs/NewDocumentDialog";
import { FileIcon } from "../components/FileIcon";
import { ItemActions } from "../components/ItemActions";
import { can, useMe } from "../components/me";
import { officeRoute, useOffice } from "../components/office";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatRelative, formatSize } from "../components/format";
import { PromptDialog } from "../dialogs/PromptDialog";
import { LibrarySettingsDialog } from "../dialogs/LibrarySettingsDialog";
import { PermissionsDialog } from "../dialogs/PermissionsDialog";
import { MapDriveDialog } from "../dialogs/MapDriveDialog";

const useStyles = makeStyles({
  head: { display: "flex", flexDirection: "column", gap: "8px", marginBottom: "8px" },
  card: { padding: 0, overflow: "hidden", position: "relative" },
  scroll: { overflowX: "auto" },
  drop: {
    position: "absolute",
    inset: 0,
    border: `2px dashed ${tokens.colorBrandStroke1}`,
    backgroundColor: tokens.colorBrandBackground2,
    opacity: 0.92,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    zIndex: 2,
    pointerEvents: "none",
  },
  nameLink: {
    color: tokens.colorNeutralForeground1,
    textDecoration: "none",
    ":hover": { textDecoration: "underline", color: tokens.colorBrandForeground1 },
  },
  muted: { color: tokens.colorNeutralForeground3 },
  hideSm: { "@media (max-width: 720px)": { display: "none" } },
});

export function LibraryView({ container, breadcrumbs }: { container: NodeDto; breadcrumbs: Crumb[] }) {
  const s = useStyles();
  const qc = useQueryClient();
  const me = useMe();
  const library = breadcrumbs[1];
  const isLibrary = container.kind === "LIBRARY";

  const children = useQuery({
    queryKey: ["children", container.id],
    queryFn: () => api<NodeDto[]>(`/api/v1/nodes/${container.id}/children`),
  });
  const settings = useQuery({
    queryKey: ["settings", library.id],
    queryFn: () => api<LibrarySettings>(`/api/v1/nodes/${library.id}/settings`),
  });
  const columns = useQuery({
    queryKey: ["columns", library.id],
    queryFn: () => api<ColumnDto[]>(`/api/v1/nodes/${library.id}/columns`),
  });

  const [dialog, setDialog] = useState<"folder" | "settings" | "permissions" | "drive" | "alert" | "rules" | null>(null);
  const [newDoc, setNewDoc] = useState<{ kind?: NewDocumentKind; template?: NodeDto } | null>(null);
  const templates = useQuery({
    queryKey: ["document-templates", container.siteId],
    queryFn: () => api<NodeDto[]>(`/api/v1/sites/${container.siteId}/document-templates`),
  });
  const [dragging, setDragging] = useState(false);
  const [progress, setProgress] = useState<{ name: string; fraction: number } | null>(null);
  const [uploadError, setUploadError] = useState<unknown>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const office = useOffice();

  const canAdd = can(container, "ADD_ITEMS");
  const minor = settings.data?.minorVersionsEnabled ?? false;
  const shownColumns = (columns.data ?? []).slice(0, 4);

  const upload = async (files: File[]) => {
    setUploadError(null);
    for (const f of files) {
      setProgress({ name: f.name, fraction: 0 });
      try {
        await uploadFiles(container.id, [f], false, (fraction) => setProgress({ name: f.name, fraction }));
      } catch (e) {
        if (e instanceof ApiError && e.code === "name_conflict" && confirm(`"${f.name}" already exists. Replace it with a new version?`)) {
          try {
            await uploadFiles(container.id, [f], true, (fraction) => setProgress({ name: f.name, fraction }));
          } catch (e2) {
            setUploadError(e2);
          }
        } else if (!(e instanceof ApiError && e.code === "name_conflict")) {
          setUploadError(e);
        }
      }
    }
    setProgress(null);
    await qc.invalidateQueries();
  };

  const title = container.title ?? container.name;

  return (
    <>
      <div className={s.head}>
        <Crumbs items={breadcrumbs} />
        {isLibrary && container.description && <Text className={s.muted}>{container.description}</Text>}
        <Toolbar aria-label={`${title} commands`} style={{ paddingLeft: 0, flexWrap: "wrap" }}>
          <Menu>
            <MenuTrigger disableButtonEnhancement>
              <ToolbarButton icon={<AddRegular />} disabled={!canAdd}>New</ToolbarButton>
            </MenuTrigger>
            <MenuPopover>
              <MenuList>
                <MenuItem icon={<FolderAddRegular />} onClick={() => setDialog("folder")}>Folder</MenuItem>
                <MenuDivider />
                <MenuItem icon={<DocumentTextRegular />} onClick={() => setNewDoc({ kind: "WORD" })}>Word document</MenuItem>
                <MenuItem icon={<TableRegular />} onClick={() => setNewDoc({ kind: "EXCEL" })}>Excel workbook</MenuItem>
                <MenuItem icon={<SlideTextRegular />} onClick={() => setNewDoc({ kind: "POWERPOINT" })}>PowerPoint presentation</MenuItem>
                {!!templates.data?.length && (
                  <>
                    <MenuDivider />
                    <MenuGroupHeader>From a template</MenuGroupHeader>
                    {templates.data.map((t) => (
                      <MenuItem key={t.id} icon={<DocumentAddRegular />} onClick={() => setNewDoc({ template: t })}>{t.title ?? t.name}</MenuItem>
                    ))}
                  </>
                )}
              </MenuList>
            </MenuPopover>
          </Menu>
          <ToolbarButton
            icon={<ArrowUploadRegular />}
            disabled={!canAdd}
            appearance="primary"
            onClick={() => fileInput.current?.click()}
          >
            Upload
          </ToolbarButton>
          {can(container, "MANAGE_PERMISSIONS") && (
            <ToolbarButton icon={<PeopleRegular />} onClick={() => setDialog("permissions")}>
              Manage access
            </ToolbarButton>
          )}
          <ToolbarButton icon={<AlertRegular />} onClick={() => setDialog("alert")}>Alert me</ToolbarButton>
          <ToolbarButton icon={<HardDriveRegular />} onClick={() => setDialog("drive")}>
            Map network drive
          </ToolbarButton>
          {isLibrary && can(container, "MANAGE_LISTS") && (
            <ToolbarButton icon={<FlashRegular />} onClick={() => setDialog("rules")}>Automate</ToolbarButton>
          )}
          {isLibrary && can(container, "MANAGE_LISTS") && (
            <ToolbarButton icon={<SettingsRegular />} onClick={() => setDialog("settings")}>
              Library settings
            </ToolbarButton>
          )}
        </Toolbar>
      </div>

      <input
        ref={fileInput}
        type="file"
        multiple
        hidden
        onChange={(e) => {
          const files = Array.from(e.target.files ?? []);
          e.target.value = "";
          if (files.length) void upload(files);
        }}
      />

      {progress && (
        <MessageBar intent="info" style={{ marginBottom: 8 }}>
          <MessageBarBody style={{ width: "100%" }}>
            Uploading {progress.name}…
            <ProgressBar value={progress.fraction} style={{ marginTop: 6 }} />
          </MessageBarBody>
        </MessageBar>
      )}
      <ErrorBar error={uploadError ?? children.error} />

      <Card
        className={s.card}
        onDragOver={(e) => {
          if (!canAdd || !e.dataTransfer.types.includes("Files")) return;
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={(e) => {
          if (!e.currentTarget.contains(e.relatedTarget as Node)) setDragging(false);
        }}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          const files = Array.from(e.dataTransfer.files);
          if (canAdd && files.length) void upload(files);
        }}
      >
        {dragging && (
          <div className={s.drop}>
            <Text size={500} weight="semibold">
              Drop files to upload to {title}
            </Text>
          </div>
        )}
        {children.isPending ? (
          <Loading />
        ) : !children.data?.length ? (
          <Empty title="This folder is empty">
            {canAdd && <Text>Drag files here, or use Upload.</Text>}
          </Empty>
        ) : (
          <div className={s.scroll}>
            <Table aria-label={`Contents of ${title}`} style={{ minWidth: 640 }}>
              <TableHeader>
                <TableRow>
                  <TableHeaderCell>Name</TableHeaderCell>
                  <TableHeaderCell style={{ width: 150 }}>Modified</TableHeaderCell>
                  <TableHeaderCell style={{ width: 160 }} className={s.hideSm}>
                    Modified by
                  </TableHeaderCell>
                  <TableHeaderCell style={{ width: 90 }} className={s.hideSm}>
                    Size
                  </TableHeaderCell>
                  <TableHeaderCell style={{ width: 70 }}>Version</TableHeaderCell>
                  {shownColumns.map((c) => (
                    <TableHeaderCell key={c.id} className={s.hideSm}>
                      {c.displayName}
                    </TableHeaderCell>
                  ))}
                  <TableHeaderCell style={{ width: 48 }} />
                </TableRow>
              </TableHeader>
              <TableBody>
                {children.data.map((n) => (
                  <TableRow key={n.id}>
                    <TableCell>
                      <TableCellLayout media={<FileIcon kind={n.kind} name={n.name} />}>
                        {n.kind === "FILE" && office.canOpen(n.name) ? (
                          <Link className={s.nameLink} to={officeRoute(n.id)}>
                            {n.name}
                          </Link>
                        ) : n.kind === "FILE" ? (
                          <a className={s.nameLink} href={contentUrl(n.id)} target="_blank" rel="noopener">
                            {n.name}
                          </a>
                        ) : (
                          <Link className={s.nameLink} to={routeFor(n.path)}>
                            {n.name}
                          </Link>
                        )}
                        {approvalBadge(n.approvalStatus) && (
                          <Badge appearance="tint" size="small" color={approvalBadge(n.approvalStatus)!.color} style={{ marginLeft: 8 }}>
                            {approvalBadge(n.approvalStatus)!.label}
                          </Badge>
                        )}
                        {n.lockedBy && (
                          <Tooltip
                            relationship="label"
                            content={
                              n.lockedBy.id === me.id
                                ? "Open in a desktop app by you"
                                : `Open in a desktop app by ${n.lockedBy.displayName} — read-only for others until closed`
                            }
                          >
                            <DocumentEditRegular
                              style={{ marginLeft: 6, verticalAlign: "middle", color: tokens.colorPaletteBerryForeground1 }}
                            />
                          </Tooltip>
                        )}
                        {n.checkedOutBy && (
                          <Tooltip
                            relationship="label"
                            content={
                              n.checkedOutBy.id === me.id
                                ? "Checked out to you"
                                : `Checked out to ${n.checkedOutBy.displayName}`
                            }
                          >
                            <LockClosedRegular
                              style={{ marginLeft: 6, verticalAlign: "middle", color: tokens.colorPaletteRedForeground1 }}
                            />
                          </Tooltip>
                        )}
                      </TableCellLayout>
                    </TableCell>
                    <TableCell>{formatRelative(n.modifiedAt)}</TableCell>
                    <TableCell className={s.hideSm}>{n.modifiedBy?.displayName}</TableCell>
                    <TableCell className={s.hideSm}>{n.kind === "FILE" ? formatSize(n.sizeBytes) : ""}</TableCell>
                    <TableCell>{n.hasDraft ? `${n.version} *` : n.version}</TableCell>
                    {shownColumns.map((c) => (
                      <TableCell key={c.id} className={s.hideSm}>
                        <ColumnValue column={c} value={n.metadata?.[c.internalName]} />
                      </TableCell>
                    ))}
                    <TableCell>
                      <ItemActions node={n} libraryId={library.id} minorVersions={minor} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Card>

      {dialog === "folder" && (
        <PromptDialog
          title="New folder"
          label="Folder name"
          submitLabel="Create"
          onClose={() => setDialog(null)}
          onSubmit={async (name) => {
            await api(`/api/v1/nodes/${container.id}/folders`, { method: "POST", body: { name } });
            await qc.invalidateQueries({ queryKey: ["children", container.id] });
          }}
        />
      )}
      {dialog === "settings" && <LibrarySettingsDialog library={container} onClose={() => setDialog(null)} />}
      {dialog === "permissions" && <PermissionsDialog node={container} onClose={() => setDialog(null)} />}
      {dialog === "drive" && <MapDriveDialog node={container} onClose={() => setDialog(null)} />}
      {dialog === "alert" && <AlertDialog node={container} onClose={() => setDialog(null)} />}
      {dialog === "rules" && <RulesDialog container={container} columns={columns.data ?? []} onClose={() => setDialog(null)} />}
      {newDoc && <NewDocumentDialog parent={container} kind={newDoc.kind} template={newDoc.template} onClose={() => setNewDoc(null)} />}
    </>
  );
}

