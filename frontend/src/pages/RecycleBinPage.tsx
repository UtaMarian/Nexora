import { useSearchParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Card,
  Table,
  TableBody,
  TableCell,
  TableCellLayout,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
} from "@fluentui/react-components";
import { api } from "../api/client";
import type { RecycleBinItem } from "../api/types";
import { FileIcon } from "../components/FileIcon";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatDate } from "../components/format";

export function RecycleBinPage() {
  const [params] = useSearchParams();
  const siteId = params.get("siteId");
  const qc = useQueryClient();
  const items = useQuery({
    queryKey: ["bin", siteId],
    queryFn: () => api<RecycleBinItem[]>(`/api/v1/recycle-bin${siteId ? `?siteId=${siteId}` : ""}`),
  });
  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => qc.invalidateQueries(),
  });

  return (
    <>
      <Text as="h1" size={700} weight="semibold" block style={{ marginBottom: 4 }}>
        Recycle bin
      </Text>
      <Text block style={{ marginBottom: 16 }}>
        Items you deleted{siteId ? " in this site" : ""}. Site owners also see what others deleted in their sites.
      </Text>
      <ErrorBar error={items.error ?? act.error} />
      <Card style={{ padding: 0, overflowX: "auto" }}>
        {items.isPending ? (
          <Loading />
        ) : !items.data?.length ? (
          <Empty title="The recycle bin is empty" />
        ) : (
          <Table aria-label="Deleted items" style={{ minWidth: 640 }}>
            <TableHeader>
              <TableRow>
                <TableHeaderCell>Name</TableHeaderCell>
                <TableHeaderCell>Original location</TableHeaderCell>
                <TableHeaderCell style={{ width: 160 }}>Deleted</TableHeaderCell>
                <TableHeaderCell style={{ width: 140 }}>Deleted by</TableHeaderCell>
                <TableHeaderCell style={{ width: 220 }} />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.data.map((i) => (
                <TableRow key={i.id}>
                  <TableCell>
                    <TableCellLayout media={<FileIcon kind={i.kind} name={i.name} />}>{i.name}</TableCellLayout>
                  </TableCell>
                  <TableCell>{i.path.slice(0, i.path.lastIndexOf("/")) || "/"}</TableCell>
                  <TableCell>{formatDate(i.deletedAt)}</TableCell>
                  <TableCell>{i.deletedBy?.displayName}</TableCell>
                  <TableCell>
                    <div style={{ display: "flex", gap: 4 }}>
                      <Button
                        size="small"
                        onClick={() => act.mutate(() => api(`/api/v1/recycle-bin/${i.id}/restore`, { method: "POST" }))}
                      >
                        Restore
                      </Button>
                      <Button
                        size="small"
                        appearance="subtle"
                        onClick={() =>
                          confirm(`Permanently delete "${i.name}" and all its versions? This cannot be undone.`) &&
                          act.mutate(() => api(`/api/v1/recycle-bin/${i.id}`, { method: "DELETE" }))
                        }
                      >
                        Delete permanently
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>
    </>
  );
}
