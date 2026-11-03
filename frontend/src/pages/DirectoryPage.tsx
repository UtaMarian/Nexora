import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Card, MessageBar, MessageBarBody, Text, makeStyles, tokens } from "@fluentui/react-components";
import { ArrowSyncRegular } from "@fluentui/react-icons";
import { api } from "../api/client";
import { ErrorBar, Loading } from "../components/States";
import { formatDate } from "../components/format";

interface DirectoryStatus {
  enabled: boolean;
  status: { lastRun?: string; lastSuccess?: string; users: number; groups: number; retired: number; error?: string };
}

const useStyles = makeStyles({
  grid: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(160px, 1fr))", gap: "12px", margin: "16px 0" },
  stat: { display: "flex", flexDirection: "column", gap: "2px" },
  label: { color: tokens.colorNeutralForeground3 },
});

/** Administrators: state of the AD/LDAP (via Keycloak) directory sync. */
export function DirectoryPage() {
  const s = useStyles();
  const qc = useQueryClient();
  const status = useQuery({ queryKey: ["directory"], queryFn: () => api<DirectoryStatus>("/api/v1/admin/directory") });
  const sync = useMutation({
    mutationFn: () => api<DirectoryStatus>("/api/v1/admin/directory/sync", { method: "POST" }),
    onSuccess: (d) => qc.setQueryData(["directory"], d),
  });
  const d = status.data;

  return (
    <>
      <Text as="h1" size={700} weight="semibold" block>Directory</Text>
      <Text block style={{ marginBottom: 12 }}>
        Users and groups from Active Directory / LDAP (through Keycloak). Directory groups can be given access to sites,
        libraries and lists like any other group; membership follows the directory.
      </Text>
      <ErrorBar error={status.error ?? sync.error} />
      {!d ? <Loading /> : !d.enabled ? (
        <MessageBar intent="info"><MessageBarBody>Directory sync is not configured on this server.</MessageBarBody></MessageBar>
      ) : (
        <>
          {d.status.error && (
            <MessageBar intent="error"><MessageBarBody>Last sync failed: {d.status.error}</MessageBarBody></MessageBar>
          )}
          <div className={s.grid}>
            <Card className={s.stat}><Text className={s.label}>Users</Text><Text size={700} weight="semibold">{d.status.users}</Text></Card>
            <Card className={s.stat}><Text className={s.label}>Groups</Text><Text size={700} weight="semibold">{d.status.groups}</Text></Card>
            <Card className={s.stat}><Text className={s.label}>Retired in last run</Text><Text size={700} weight="semibold">{d.status.retired}</Text></Card>
            <Card className={s.stat}><Text className={s.label}>Last successful sync</Text><Text weight="semibold">{d.status.lastSuccess ? formatDate(d.status.lastSuccess) : "Never"}</Text></Card>
          </div>
          <Button appearance="primary" icon={<ArrowSyncRegular />} disabled={sync.isPending} onClick={() => sync.mutate()}>
            {sync.isPending ? "Syncing…" : "Sync now"}
          </Button>
        </>
      )}
    </>
  );
}
