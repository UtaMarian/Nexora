import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Persona,
  Tab,
  TabList,
  Text,
  Tooltip,
} from "@fluentui/react-components";
import { DismissRegular } from "@fluentui/react-icons";
import { api } from "../api/client";
import type { NodeDto, PrincipalRef } from "../api/types";
import { can } from "../components/me";
import { ErrorBar, Loading } from "../components/States";
import { PeoplePicker } from "./PeoplePicker";

/** Site members: the site's Owners / Members / Visitors groups. */
export function GroupsDialog({ site, onClose }: { site: NodeDto; onClose: () => void }) {
  const qc = useQueryClient();
  const groups = useQuery({
    queryKey: ["groups", site.id],
    queryFn: () => api<PrincipalRef[]>(`/api/v1/sites/${site.id}/groups`),
  });
  const [selected, setSelected] = useState<string | null>(null);
  const groupId = selected ?? groups.data?.[0]?.id ?? null;
  const members = useQuery({
    queryKey: ["members", groupId],
    queryFn: () => api<PrincipalRef[]>(`/api/v1/principals/${groupId}/members`),
    enabled: !!groupId,
  });
  const [who, setWho] = useState<PrincipalRef | null>(null);
  const canManage = can(site, "MANAGE_WEB");

  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => qc.invalidateQueries(),
  });

  const hint: Record<string, string> = {
    owners: "Full control of the site, its permissions and its groups.",
    members: "Can add, edit and delete content, and create libraries.",
    visitors: "Can view content.",
  };
  const role = groups.data?.find((g) => g.id === groupId)?.login.split("-").pop() ?? "";

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 560 }}>
        <DialogBody>
          <DialogTitle>Site members</DialogTitle>
          <DialogContent>
            <ErrorBar error={groups.error ?? members.error ?? act.error} />
            {groups.isPending ? (
              <Loading />
            ) : (
              <>
                <TabList selectedValue={groupId} onTabSelect={(_, d) => setSelected(d.value as string)}>
                  {groups.data?.map((g) => (
                    <Tab key={g.id} value={g.id}>
                      {g.displayName.replace(site.title ?? "", "").trim() || g.displayName}
                    </Tab>
                  ))}
                </TabList>
                <Text size={200} block style={{ margin: "8px 0 12px" }}>
                  {hint[role]}
                </Text>
                {canManage && (
                  <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
                    <PeoplePicker onSelect={setWho} placeholder="Add a person or group" />
                    <Button
                      appearance="primary"
                      disabled={!who || !groupId}
                      onClick={() =>
                        who &&
                        act.mutate(() =>
                          api(`/api/v1/principals/${groupId}/members`, { method: "POST", body: { principalId: who.id } }),
                        )
                      }
                    >
                      Add
                    </Button>
                  </div>
                )}
                {members.isPending ? (
                  <Loading />
                ) : members.data?.length ? (
                  <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                    {members.data.map((m) => (
                      <div key={m.id} style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                        <Persona
                          name={m.displayName}
                          secondaryText={m.kind === "GROUP" ? "Group" : m.login}
                          avatar={{ color: "colorful", shape: m.kind === "GROUP" ? "square" : "circular" }}
                        />
                        {canManage && (
                          <Tooltip content="Remove" relationship="label">
                            <Button
                              appearance="subtle"
                              icon={<DismissRegular />}
                              onClick={() =>
                                act.mutate(() =>
                                  api(`/api/v1/principals/${groupId}/members/${m.id}`, { method: "DELETE" }),
                                )
                              }
                            />
                          </Tooltip>
                        )}
                      </div>
                    ))}
                  </div>
                ) : (
                  <Text size={200}>No members yet.</Text>
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
