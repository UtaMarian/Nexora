import { Fragment, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Card,
  Input,
  Menu,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
  Toolbar,
  ToolbarButton,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import {
  AddRegular,
  DeleteRegular,
  EditRegular,
  FilterRegular,
  MoreHorizontalRegular,
  PeopleRegular,
  SettingsRegular,
} from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { ColumnDto, Crumb, NodeDto } from "../api/types";
import { Crumbs } from "../components/Crumbs";
import { can } from "../components/me";
import { ColumnValue, valueText } from "../components/columns";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatRelative } from "../components/format";
import { ItemDialog } from "../dialogs/ItemDialog";
import { LibrarySettingsDialog } from "../dialogs/LibrarySettingsDialog";
import { PermissionsDialog } from "../dialogs/PermissionsDialog";

const useStyles = makeStyles({
  head: { display: "flex", flexDirection: "column", gap: "8px", marginBottom: "8px" },
  bar: { display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" },
  card: { padding: 0, overflow: "hidden" },
  scroll: { overflowX: "auto" },
  row: { cursor: "pointer" },
  sortable: { cursor: "pointer", userSelect: "none", whiteSpace: "nowrap" },
  muted: { color: tokens.colorNeutralForeground3 },
});

type Sort = { key: string; dir: 1 | -1 };

/** A SharePoint-style list: sortable, filterable items with a form per item. */
export function ListView(props: { list: NodeDto; breadcrumbs: Crumb[]; openItemId?: string }) {
  const { list, breadcrumbs } = props;
  const s = useStyles();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const items = useQuery({ queryKey: ["children", list.id], queryFn: () => api<NodeDto[]>(`/api/v1/nodes/${list.id}/children`) });
  const columns = useQuery({ queryKey: ["columns", list.id], queryFn: () => api<ColumnDto[]>(`/api/v1/nodes/${list.id}/columns`) });

  const [filter, setFilter] = useState("");
  const [sort, setSort] = useState<Sort>({ key: "id", dir: -1 });
  const [dialog, setDialog] = useState<"new" | "settings" | "permissions" | null>(null);
  const [editing, setEditing] = useState<NodeDto | null>(null);
  const cols = columns.data ?? [];
  const deepLinked = props.openItemId ? items.data?.find((i) => i.id === props.openItemId) : undefined;

  const rows = useMemo(() => {
    const q = filter.trim().toLowerCase();
    const cell = (n: NodeDto, key: string): string | number => {
      if (key === "id") return Number(n.name);
      if (key === "title") return (n.title ?? "").toLowerCase();
      if (key === "modified") return n.modifiedAt;
      const v = n.metadata?.[key];
      return typeof v === "number" ? v : valueText(v).toLowerCase();
    };
    return (items.data ?? [])
      .filter((n) => !q || [n.title ?? "", ...Object.values(n.metadata ?? {}).map(valueText)].some((t) => t.toLowerCase().includes(q)))
      .sort((a, b) => {
        const x = cell(a, sort.key);
        const y = cell(b, sort.key);
        return (x < y ? -1 : x > y ? 1 : 0) * sort.dir;
      });
  }, [items.data, filter, sort]);

  const header = (key: string, label: string) => (
    <TableHeaderCell
      className={s.sortable}
      onClick={() => setSort((p) => ({ key, dir: p.key === key ? (p.dir === 1 ? -1 : 1) : 1 }))}
      sortDirection={sort.key === key ? (sort.dir === 1 ? "ascending" : "descending") : undefined}
    >
      {label}
    </TableHeaderCell>
  );

  const remove = async (item: NodeDto) => {
    if (!confirm(`Delete "${item.title}"? It goes to the recycle bin.`)) return;
    await api(`/api/v1/nodes/${item.id}`, { method: "DELETE" });
    await qc.invalidateQueries();
  };

  const closeItem = () => {
    setEditing(null);
    if (props.openItemId) navigate(routeFor(list.path), { replace: true });
  };

  return (
    <>
      <div className={s.head}>
        <Crumbs items={breadcrumbs.slice(0, breadcrumbs.findIndex((c) => c.id === list.id) + 1)} />
        {list.description && <Text className={s.muted}>{list.description}</Text>}
        <div className={s.bar}>
          <Toolbar aria-label={`${list.title} commands`} style={{ paddingLeft: 0, flexWrap: "wrap" }}>
            <ToolbarButton appearance="primary" icon={<AddRegular />} disabled={!can(list, "ADD_ITEMS")} onClick={() => setDialog("new")}>
              New item
            </ToolbarButton>
            {can(list, "MANAGE_PERMISSIONS") && (
              <ToolbarButton icon={<PeopleRegular />} onClick={() => setDialog("permissions")}>
                Manage access
              </ToolbarButton>
            )}
            {can(list, "MANAGE_LISTS") && (
              <ToolbarButton icon={<SettingsRegular />} onClick={() => setDialog("settings")}>
                List settings
              </ToolbarButton>
            )}
          </Toolbar>
          <Input
            contentBefore={<FilterRegular />}
            placeholder="Filter items"
            value={filter}
            onChange={(_, d) => setFilter(d.value)}
            style={{ marginLeft: "auto", minWidth: 200 }}
            aria-label="Filter items"
          />
        </div>
      </div>

      <ErrorBar error={items.error ?? columns.error} />
      <Card className={s.card}>
        {items.isPending || columns.isPending ? (
          <Loading />
        ) : !rows.length ? (
          <Empty title={filter ? "No items match the filter" : "This list is empty"}>
            {!filter && can(list, "ADD_ITEMS") && <Text>Use New item to add the first one.</Text>}
          </Empty>
        ) : (
          <div className={s.scroll}>
            <Table aria-label={`Items in ${list.title}`} style={{ minWidth: 480 + cols.length * 120 }}>
              <TableHeader>
                <TableRow>
                  {header("id", "ID")}
                  {header("title", "Title")}
                  {cols.map((c) => <Fragment key={c.id}>{header(c.internalName, c.displayName)}</Fragment>)}
                  {header("modified", "Modified")}
                  <TableHeaderCell style={{ width: 48 }} />
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((n) => (
                  <TableRow key={n.id} className={s.row} onClick={() => setEditing(n)}>
                    <TableCell style={{ width: 56 }}>{n.name}</TableCell>
                    <TableCell>
                      <Text weight="semibold">{n.title}</Text>
                    </TableCell>
                    {cols.map((c) => (
                      <TableCell key={c.id}>
                        <ColumnValue column={c} value={n.metadata?.[c.internalName]} />
                      </TableCell>
                    ))}
                    <TableCell title={n.modifiedBy?.displayName}>{formatRelative(n.modifiedAt)}</TableCell>
                    <TableCell onClick={(e) => e.stopPropagation()}>
                      <Menu positioning="below-end">
                        <MenuTrigger disableButtonEnhancement>
                          <Button appearance="subtle" size="small" icon={<MoreHorizontalRegular />} aria-label={`Actions for ${n.title}`} />
                        </MenuTrigger>
                        <MenuPopover>
                          <MenuList>
                            <MenuItem icon={<EditRegular />} onClick={() => setEditing(n)}>
                              {can(n, "EDIT_ITEMS") ? "Edit" : "View"}
                            </MenuItem>
                            {can(n, "DELETE_ITEMS") && (
                              <MenuItem icon={<DeleteRegular />} onClick={() => remove(n)}>
                                Delete
                              </MenuItem>
                            )}
                          </MenuList>
                        </MenuPopover>
                      </Menu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Card>

      {dialog === "new" && <ItemDialog list={list} columns={cols} onClose={() => setDialog(null)} />}
      {(editing ?? deepLinked) && <ItemDialog list={list} columns={cols} item={(editing ?? deepLinked)!} onClose={closeItem} />}
      {dialog === "settings" && <LibrarySettingsDialog library={list} onClose={() => setDialog(null)} />}
      {dialog === "permissions" && <PermissionsDialog node={list} onClose={() => setDialog(null)} />}
    </>
  );
}

