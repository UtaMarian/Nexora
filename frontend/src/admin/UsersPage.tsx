import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Badge, Input, Switch, Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text, tokens } from "@fluentui/react-components";
import { api } from "../api/client";
import { useMe } from "../components/me";
import { Empty, ErrorBar, Loading } from "../components/States";
import type { UserRow } from "./types";

export function UsersPage() {
  const me = useMe();
  const qc = useQueryClient();
  const [q, setQ] = useState("");
  const [error, setError] = useState<unknown>(null);
  const users = useQuery({
    queryKey: ["admin", "users", q],
    queryFn: () => api<UserRow[]>(`/api/v1/admin/users?q=${encodeURIComponent(q)}`),
  });
  const patch = async (u: UserRow, body: Partial<Pick<UserRow, "enabled" | "admin">>) => {
    setError(null);
    try {
      await api(`/api/v1/admin/users/${u.id}`, { method: "PATCH", body });
      await qc.invalidateQueries({ queryKey: ["admin", "users"] });
    } catch (e) {
      setError(e);
    }
  };
  return (
    <>
      <Text as="h1" size={700} weight="semibold">Users</Text>
      <Text block style={{ margin: "4px 0 12px", color: tokens.colorNeutralForeground3 }}>
        You can't change your own account here, so an administrator can't lock themselves out.
      </Text>
      <Input placeholder="Search users" aria-label="Search users" value={q} onChange={(_, d) => setQ(d.value)} style={{ marginBottom: 12, width: 280, maxWidth: "100%" }} />
      <ErrorBar error={error ?? users.error} />
      {users.isPending ? <Loading /> : !users.data?.length ? <Empty title="No users found" /> : (
        <div style={{ overflowX: "auto" }}>
          <Table aria-label="Users" style={{ minWidth: 620 }}>
            <TableHeader>
              <TableRow>
                <TableHeaderCell>Name</TableHeaderCell>
                <TableHeaderCell style={{ width: 110 }}>Source</TableHeaderCell>
                <TableHeaderCell style={{ width: 110 }}>Enabled</TableHeaderCell>
                <TableHeaderCell style={{ width: 130 }}>Administrator</TableHeaderCell>
              </TableRow>
            </TableHeader>
            <TableBody>
              {users.data.map((u) => (
                <TableRow key={u.id}>
                  <TableCell>
                    {u.displayName}
                    <Text block size={200} style={{ color: tokens.colorNeutralForeground3 }}>{u.login}{u.email ? ` · ${u.email}` : ""}</Text>
                  </TableCell>
                  <TableCell><Badge appearance="tint" color={u.directory ? "brand" : "informative"}>{u.directory ? "Directory" : "Local"}</Badge></TableCell>
                  <TableCell>
                    <Switch aria-label={`${u.displayName} enabled`} checked={u.enabled} disabled={u.id === me.id} onChange={(_, d) => patch(u, { enabled: d.checked })} />
                  </TableCell>
                  <TableCell>
                    <Switch aria-label={`${u.displayName} administrator`} checked={u.admin} disabled={u.id === me.id} onChange={(_, d) => patch(u, { admin: d.checked })} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </>
  );
}
