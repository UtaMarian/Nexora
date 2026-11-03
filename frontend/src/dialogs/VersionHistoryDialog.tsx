import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Badge,
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
} from "@fluentui/react-components";
import { api, contentUrl } from "../api/client";
import type { NodeDto, VersionDto } from "../api/types";
import { can } from "../components/me";
import { ErrorBar, Loading } from "../components/States";
import { formatDate, formatSize } from "../components/format";

export function VersionHistoryDialog({ file, onClose }: { file: NodeDto; onClose: () => void }) {
  const qc = useQueryClient();
  const versions = useQuery({
    queryKey: ["versions", file.id],
    queryFn: () => api<VersionDto[]>(`/api/v1/nodes/${file.id}/versions`),
  });
  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => qc.invalidateQueries(),
  });
  const canEdit = can(file, "EDIT_ITEMS") && !file.checkedOutBy;
  const canDelete = can(file, "DELETE_ITEMS");

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 860 }}>
        <DialogBody>
          <DialogTitle>Version history: {file.name}</DialogTitle>
          <DialogContent>
            <ErrorBar error={versions.error ?? act.error} />
            {versions.isPending ? (
              <Loading />
            ) : (
              <div style={{ overflowX: "auto" }}>
                <Table size="small" aria-label="Versions">
                  <TableHeader>
                    <TableRow>
                      <TableHeaderCell style={{ width: 90 }}>Version</TableHeaderCell>
                      <TableHeaderCell>Modified</TableHeaderCell>
                      <TableHeaderCell>Modified by</TableHeaderCell>
                      <TableHeaderCell style={{ width: 80 }}>Size</TableHeaderCell>
                      <TableHeaderCell>Comment</TableHeaderCell>
                      <TableHeaderCell style={{ width: 220 }} />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {versions.data?.map((v) => (
                      <TableRow key={v.id}>
                        <TableCell>
                          {v.label}{" "}
                          {v.current && (
                            <Badge size="small" appearance="tint">
                              current
                            </Badge>
                          )}
                        </TableCell>
                        <TableCell>{formatDate(v.createdAt)}</TableCell>
                        <TableCell>{v.createdBy?.displayName}</TableCell>
                        <TableCell>{formatSize(v.sizeBytes)}</TableCell>
                        <TableCell>{v.comment}</TableCell>
                        <TableCell>
                          <div style={{ display: "flex", gap: 4 }}>
                            <Button
                              size="small"
                              as="a"
                              href={contentUrl(file.id, { versionId: v.id, download: true })}
                            >
                              Download
                            </Button>
                            {!v.current && canEdit && (
                              <Button
                                size="small"
                                onClick={() =>
                                  act.mutate(() =>
                                    api(`/api/v1/nodes/${file.id}/versions/${v.id}/restore`, { method: "POST" }),
                                  )
                                }
                              >
                                Restore
                              </Button>
                            )}
                            {!v.current && canDelete && (
                              <Button
                                size="small"
                                appearance="subtle"
                                onClick={() =>
                                  confirm(`Delete version ${v.label}? This cannot be undone.`) &&
                                  act.mutate(() =>
                                    api(`/api/v1/nodes/${file.id}/versions/${v.id}`, { method: "DELETE" }),
                                  )
                                }
                              >
                                Delete
                              </Button>
                            )}
                          </div>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
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
