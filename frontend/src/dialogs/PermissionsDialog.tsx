import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Dropdown,
  MessageBar,
  MessageBarActions,
  MessageBarBody,
  Option,
  Persona,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
  Tooltip,
} from "@fluentui/react-components";
import { DismissRegular } from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { AnonymousStatus, NodeDto, PermissionLevel, Permissions, PrincipalRef } from "../api/types";
import { ErrorBar, Loading } from "../components/States";
import { levelLabel } from "../components/format";
import { PeoplePicker } from "./PeoplePicker";

const LEVELS: PermissionLevel[] = ["READ", "CONTRIBUTE", "EDIT", "FULL_CONTROL"];
const ANONYMOUS_LOGIN = "nexora-anonymous";

/** SharePoint's anonymous access: a whole site, or single libraries and lists in a private site. */
function AnonymousChoice({ node, onChanged }: { node: NodeDto; onChanged: () => void }) {
  const qc = useQueryClient();
  const key = ["anonymous", node.id];
  const status = useQuery({ queryKey: key, queryFn: () => api<AnonymousStatus>(`/api/v1/nodes/${node.id}/anonymous`) });
  const change = useMutation({
    mutationFn: (enabled: boolean) => api<AnonymousStatus>(`/api/v1/nodes/${node.id}/anonymous`, { method: "PUT", body: { enabled } }),
    onSuccess: (s) => {
      qc.setQueryData(key, s);
      onChanged();
    },
  });
  const s = status.data;
  if (!s?.available) return null;
  const fromElsewhere = s.enabled && s.inherited;
  return (
    <div style={{ marginBottom: 12 }}>
      <ErrorBar error={change.error} />
      <Switch
        checked={s.enabled}
        disabled={fromElsewhere || change.isPending}
        label="Anyone can read this without signing in"
        onChange={(_, d) => change.mutate(d.checked)}
      />
      <Text block size={200}>
        {fromElsewhere ? <>This comes from <Link to={routeFor(s.sourcePath)}>{s.sourcePath}</Link>.</>
          : node.kind === "SITE" ? "Visitors can open everything here that does not have its own permissions, and see the names of the people who changed it."
          : "Visitors can open this without seeing the rest of the site."}
      </Text>
    </div>
  );
}

export function PermissionsDialog({ node, onClose }: { node: NodeDto; onClose: () => void }) {
  const qc = useQueryClient();
  const key = ["permissions", node.id];
  const perms = useQuery({ queryKey: key, queryFn: () => api<Permissions>(`/api/v1/nodes/${node.id}/permissions`) });
  const [who, setWho] = useState<PrincipalRef | null>(null);
  const [level, setLevel] = useState<PermissionLevel>("READ");

  const act = useMutation({
    mutationFn: (fn: () => Promise<Permissions>) => fn(),
    onSuccess: (p) => {
      qc.setQueryData(key, p);
      qc.invalidateQueries({ predicate: (q) => q.queryKey[0] !== "permissions" });
    },
  });

  const base = `/api/v1/nodes/${node.id}/permissions`;
  const p = perms.data;
  const unique = p && !p.inheritsPermissions;

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 720 }}>
        <DialogBody>
          <DialogTitle>Manage access: {node.title ?? node.name}</DialogTitle>
          <DialogContent>
            <ErrorBar error={perms.error ?? act.error} />
            {!p ? (
              <Loading />
            ) : (
              <>
                {p.inheritsPermissions ? (
                  <MessageBar intent="info" style={{ marginBottom: 12 }}>
                    <MessageBarBody>
                      Inherits permissions from{" "}
                      <Link to={routeFor(p.sourcePath)} onClick={onClose}>
                        {p.sourcePath}
                      </Link>
                      .
                    </MessageBarBody>
                    <MessageBarActions>
                      <Button
                        size="small"
                        onClick={() => act.mutate(() => api(`${base}/break-inheritance`, { method: "POST", body: {} }))}
                      >
                        Stop inheriting
                      </Button>
                    </MessageBarActions>
                  </MessageBar>
                ) : node.kind !== "SITE" ? (
                  <MessageBar intent="warning" style={{ marginBottom: 12 }}>
                    <MessageBarBody>This item has unique permissions.</MessageBarBody>
                    <MessageBarActions>
                      <Button
                        size="small"
                        onClick={() => act.mutate(() => api(`${base}/reset-inheritance`, { method: "POST" }))}
                      >
                        Delete unique permissions
                      </Button>
                    </MessageBarActions>
                  </MessageBar>
                ) : null}

                {(node.kind === "SITE" || node.kind === "LIBRARY" || node.kind === "LIST") && (
                  <AnonymousChoice node={node} onChanged={() => qc.invalidateQueries({ queryKey: key })} />
                )}
                <Table size="small" aria-label="Permissions">
                  <TableHeader>
                    <TableRow>
                      <TableHeaderCell>Name</TableHeaderCell>
                      <TableHeaderCell style={{ width: 180 }}>Permission level</TableHeaderCell>
                      <TableHeaderCell style={{ width: 48 }} />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {p.entries.map((e) => (
                      <TableRow key={e.principal.id}>
                        <TableCell>
                          <Persona
                            name={e.principal.displayName}
                            secondaryText={e.principal.kind === "GROUP" ? "Group" : e.principal.login}
                            avatar={{ shape: e.principal.kind === "GROUP" ? "square" : "circular", color: "colorful" }}
                            size="small"
                          />
                        </TableCell>
                        <TableCell>
                          {unique && e.principal.login !== ANONYMOUS_LOGIN ? (
                            <Dropdown
                              size="small"
                              value={levelLabel[e.level]}
                              selectedOptions={[e.level]}
                              onOptionSelect={(_, d) =>
                                act.mutate(() =>
                                  api(`${base}/${e.principal.id}`, { method: "PUT", body: { level: d.optionValue } }),
                                )
                              }
                              style={{ minWidth: 150 }}
                            >
                              {LEVELS.map((l) => (
                                <Option key={l} value={l}>
                                  {levelLabel[l]}
                                </Option>
                              ))}
                            </Dropdown>
                          ) : (
                            levelLabel[e.level]
                          )}
                        </TableCell>
                        <TableCell>
                          {unique && (
                            <Tooltip content="Remove access" relationship="label">
                              <Button
                                appearance="subtle"
                                size="small"
                                icon={<DismissRegular />}
                                onClick={() =>
                                  act.mutate(() => api(`${base}/${e.principal.id}`, { method: "DELETE" }))
                                }
                              />
                            </Tooltip>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>

                {unique && (
                  <div style={{ display: "flex", gap: 8, marginTop: 16, flexWrap: "wrap", alignItems: "center" }}>
                    <Text weight="semibold" style={{ width: "100%" }}>
                      Grant access
                    </Text>
                    <PeoplePicker onSelect={setWho} />
                    <Dropdown
                      value={levelLabel[level]}
                      selectedOptions={[level]}
                      onOptionSelect={(_, d) => setLevel(d.optionValue as PermissionLevel)}
                      style={{ minWidth: 150 }}
                    >
                      {LEVELS.map((l) => (
                        <Option key={l} value={l}>
                          {levelLabel[l]}
                        </Option>
                      ))}
                    </Dropdown>
                    <Button
                      appearance="primary"
                      disabled={!who}
                      onClick={() =>
                        who && act.mutate(() => api(`${base}/${who.id}`, { method: "PUT", body: { level } }))
                      }
                    >
                      Grant
                    </Button>
                  </div>
                )}
              </>
            )}
          </DialogContent>
          <DialogActions>
            <Button onClick={onClose}>Close</Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}
