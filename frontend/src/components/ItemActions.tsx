import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Menu,
  MenuDivider,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  Toast,
  ToastTitle,
  useToastController,
} from "@fluentui/react-components";
import {
  ArrowDownloadRegular,
  ArrowUploadRegular,
  CheckmarkCircleRegular,
  DeleteRegular,
  DesktopRegular,
  DismissCircleRegular,
  DocumentEditRegular,
  EditRegular,
  HistoryRegular,
  InfoRegular,
  LockClosedRegular,
  LockOpenRegular,
  MoreHorizontalRegular,
  OpenRegular,
  PeopleRegular,
} from "@fluentui/react-icons";
import { api, ApiError, contentUrl, routeFor } from "../api/client";
import type { NodeDto } from "../api/types";
import { can, useMe } from "./me";
import { desktopLink, officeRoute, useOffice } from "./office";
import { PromptDialog } from "../dialogs/PromptDialog";
import { PropertiesDialog } from "../dialogs/PropertiesDialog";
import { VersionHistoryDialog } from "../dialogs/VersionHistoryDialog";
import { PermissionsDialog } from "../dialogs/PermissionsDialog";
import { CheckInDialog } from "../dialogs/CheckInDialog";

export const TOASTER_ID = "nexora-toaster";

type DialogKind = "rename" | "properties" | "versions" | "permissions" | "checkin" | null;

/** The "…" menu for a file or folder. */
export function ItemActions(props: {
  node: NodeDto;
  libraryId: string;
  minorVersions: boolean;
  onDeleted?: () => void;
}) {
  const { node } = props;
  const me = useMe();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { dispatchToast } = useToastController(TOASTER_ID);
  const [dialog, setDialog] = useState<DialogKind>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const office = useOffice();

  const isFile = node.kind === "FILE";
  const mine = node.checkedOutBy?.id === me.id;
  const lockedByOther = !!node.checkedOutBy && !mine;
  const canEdit = can(node, "EDIT_ITEMS") && !lockedByOther;
  const openElsewhere = !!node.lockedBy && node.lockedBy.id !== me.id;
  const desktop = isFile ? desktopLink(node.name, node.path, canEdit && !openElsewhere) : null;

  const notify = (title: string, intent: "success" | "error" = "success") =>
    dispatchToast(
      <Toast>
        <ToastTitle>{title}</ToastTitle>
      </Toast>,
      { intent },
    );

  const run = async (fn: () => Promise<unknown>, success: string) => {
    try {
      await fn();
      await qc.invalidateQueries();
      notify(success);
    } catch (e) {
      notify(e instanceof ApiError ? e.message : "Something went wrong", "error");
    }
  };

  const replaceContent = async (file: File) => {
    const token = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="))?.split("=")[1];
    await run(async () => {
      const res = await fetch(`/api/v1/nodes/${node.id}/content`, {
        method: "PUT",
        body: file,
        credentials: "same-origin",
        headers: {
          "Content-Type": file.type || "application/octet-stream",
          ...(token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}),
        },
      });
      if (!res.ok) {
        const b = await res.json().catch(() => ({}));
        throw new ApiError(res.status, b.code ?? "error", b.detail ?? "Upload failed");
      }
    }, mine ? "Draft saved. Check in to publish it." : "New version uploaded");
  };

  return (
    <>
      <Menu positioning="below-end">
        <MenuTrigger disableButtonEnhancement>
          <Button appearance="subtle" size="small" icon={<MoreHorizontalRegular />} aria-label={`Actions for ${node.name}`} />
        </MenuTrigger>
        <MenuPopover>
          <MenuList>
            {isFile ? (
              <>
                {office.canOpen(node.name) && (
                  <MenuItem icon={<DocumentEditRegular />} onClick={() => navigate(officeRoute(node.id))}>
                    {office.canEdit(node.name) && canEdit ? "Open in browser" : "View in browser"}
                  </MenuItem>
                )}
                {desktop && (
                  <MenuItem icon={<DesktopRegular />} onClick={() => (window.location.href = desktop.href)}>
                    Open in {desktop.app}
                  </MenuItem>
                )}
                <MenuItem icon={<OpenRegular />} onClick={() => window.open(contentUrl(node.id), "_blank", "noopener")}>
                  {office.canOpen(node.name) ? "Open file" : "Open"}
                </MenuItem>
                <MenuItem
                  icon={<ArrowDownloadRegular />}
                  onClick={() => (window.location.href = contentUrl(node.id, { download: true }))}
                >
                  Download
                </MenuItem>
              </>
            ) : (
              <MenuItem icon={<OpenRegular />} onClick={() => navigate(routeFor(node.path))}>
                Open
              </MenuItem>
            )}
            <MenuItem icon={<InfoRegular />} onClick={() => setDialog("properties")} disabled={!canEdit}>
              Properties
            </MenuItem>
            <MenuItem icon={<EditRegular />} onClick={() => setDialog("rename")} disabled={!canEdit}>
              Rename
            </MenuItem>

            {isFile && (
              <>
                <MenuDivider />
                {canEdit && (
                  <MenuItem icon={<ArrowUploadRegular />} onClick={() => fileInput.current?.click()}>
                    Upload new version
                  </MenuItem>
                )}
                {!node.checkedOutBy && can(node, "EDIT_ITEMS") && (
                  <MenuItem
                    icon={<LockClosedRegular />}
                    onClick={() => run(() => api(`/api/v1/nodes/${node.id}/checkout`, { method: "POST" }), "Checked out")}
                  >
                    Check out
                  </MenuItem>
                )}
                {node.checkedOutBy && (mine || can(node, "OVERRIDE_CHECKOUT")) && (
                  <>
                    <MenuItem icon={<LockOpenRegular />} onClick={() => setDialog("checkin")}>
                      Check in
                    </MenuItem>
                    <MenuItem
                      icon={<DismissCircleRegular />}
                      onClick={() =>
                        confirm("Discard the check out? Unsaved draft changes are lost.") &&
                        run(
                          () => api(`/api/v1/nodes/${node.id}/discard-checkout`, { method: "POST" }),
                          "Check out discarded",
                        )
                      }
                    >
                      Discard check out
                    </MenuItem>
                  </>
                )}
                {props.minorVersions && node.version && !node.version.endsWith(".0") && !node.checkedOutBy && canEdit && (
                  <MenuItem
                    icon={<CheckmarkCircleRegular />}
                    onClick={() =>
                      run(() => api(`/api/v1/nodes/${node.id}/publish`, { method: "POST", body: {} }), "Published")
                    }
                  >
                    Publish a major version
                  </MenuItem>
                )}
                <MenuItem icon={<HistoryRegular />} onClick={() => setDialog("versions")}>
                  Version history
                </MenuItem>
              </>
            )}

            <MenuDivider />
            {can(node, "MANAGE_PERMISSIONS") && (
              <MenuItem icon={<PeopleRegular />} onClick={() => setDialog("permissions")}>
                Manage access
              </MenuItem>
            )}
            <MenuItem
              icon={<DeleteRegular />}
              disabled={!can(node, "DELETE_ITEMS") || lockedByOther}
              onClick={() =>
                confirm(`Move "${node.name}" to the recycle bin?`) &&
                run(async () => {
                  await api(`/api/v1/nodes/${node.id}`, { method: "DELETE" });
                  props.onDeleted?.();
                }, "Moved to the recycle bin")
              }
            >
              Delete
            </MenuItem>
          </MenuList>
        </MenuPopover>
      </Menu>

      <input
        ref={fileInput}
        type="file"
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          e.target.value = "";
          if (f) void replaceContent(f);
        }}
      />

      {dialog === "rename" && (
        <PromptDialog
          title="Rename"
          label="Name"
          initial={node.name}
          onClose={() => setDialog(null)}
          onSubmit={async (name) => {
            await api(`/api/v1/nodes/${node.id}`, { method: "PATCH", body: { name } });
            await qc.invalidateQueries();
          }}
        />
      )}
      {dialog === "properties" && (
        <PropertiesDialog node={node} libraryId={props.libraryId} onClose={() => setDialog(null)} />
      )}
      {dialog === "versions" && <VersionHistoryDialog file={node} onClose={() => setDialog(null)} />}
      {dialog === "permissions" && <PermissionsDialog node={node} onClose={() => setDialog(null)} />}
      {dialog === "checkin" && (
        <CheckInDialog file={node} minorVersions={props.minorVersions} onClose={() => setDialog(null)} />
      )}
    </>
  );
}
