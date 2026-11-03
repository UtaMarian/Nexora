import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  Button,
  Card,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
} from "@fluentui/react-components";
import { api } from "../api/client";
import type { AuditEvent, Page } from "../api/types";
import { ErrorBar, Loading } from "../components/States";
import { formatDate } from "../components/format";

const PAGE_SIZE = 50;

export function AuditPage() {
  const [params] = useSearchParams();
  const siteId = params.get("siteId");
  const [page, setPage] = useState(0);
  const events = useQuery({
    queryKey: ["audit", siteId, page],
    queryFn: () => {
      const q = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
      if (siteId) q.set("siteId", siteId);
      return api<Page<AuditEvent>>(`/api/v1/audit?${q}`);
    },
    placeholderData: keepPreviousData,
  });
  const total = events.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <>
      <Text as="h1" size={700} weight="semibold" block style={{ marginBottom: 4 }}>
        Audit log
      </Text>
      <Text block style={{ marginBottom: 16 }}>
        Every view, change, share and permission change{siteId ? " in this site" : " across all sites"}.
      </Text>
      <ErrorBar error={events.error} />
      <Card style={{ padding: 0, overflowX: "auto" }}>
        {events.isPending ? (
          <Loading />
        ) : (
          <Table size="small" aria-label="Audit events" style={{ minWidth: 760 }}>
            <TableHeader>
              <TableRow>
                <TableHeaderCell style={{ width: 170 }}>When</TableHeaderCell>
                <TableHeaderCell style={{ width: 140 }}>Who</TableHeaderCell>
                <TableHeaderCell style={{ width: 200 }}>Action</TableHeaderCell>
                <TableHeaderCell>Item</TableHeaderCell>
                <TableHeaderCell>Details</TableHeaderCell>
              </TableRow>
            </TableHeader>
            <TableBody>
              {events.data?.items.map((e) => (
                <TableRow key={e.id}>
                  <TableCell>{formatDate(e.occurredAt)}</TableCell>
                  <TableCell>{e.actor ?? "—"}</TableCell>
                  <TableCell>{e.action.replaceAll("_", " ").toLowerCase()}</TableCell>
                  <TableCell style={{ wordBreak: "break-all" }}>{e.path}</TableCell>
                  <TableCell>
                    {e.details &&
                      Object.entries(e.details)
                        .map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(", ") : String(v)}`)
                        .join(" · ")}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>
      <div style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 12 }}>
        <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Newer
        </Button>
        <Text>
          Page {page + 1} of {pages} · {total} events
        </Text>
        <Button disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>
          Older
        </Button>
      </div>
    </>
  );
}
