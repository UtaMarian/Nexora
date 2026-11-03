import { Button, Dropdown, Input, Option, Tooltip, makeStyles } from "@fluentui/react-components";
import { AddRegular, ArrowDownRegular, ArrowUpRegular, DismissRegular } from "@fluentui/react-icons";
import type { Link, NodeDto } from "../api/types";

const useStyles = makeStyles({
  row: { display: "flex", gap: "6px", alignItems: "center", marginBottom: "6px", flexWrap: "wrap" },
  label: { width: "170px", "@media (max-width: 560px)": { width: "100%" } },
  url: { flex: 1, minWidth: "180px" },
  actions: { display: "flex", gap: "6px", flexWrap: "wrap", marginTop: "4px" },
});

/** Editable, reorderable list of links, with a picker for the site's own libraries and lists. */
export function LinkEditor(props: { links: Link[]; onChange: (links: Link[]) => void; siteContent: NodeDto[]; max: number }) {
  const s = useStyles();
  const { links, onChange } = props;
  const set = (i: number, patch: Partial<Link>) => onChange(links.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const move = (i: number, by: number) => {
    const next = [...links];
    const [item] = next.splice(i, 1);
    next.splice(i + by, 0, item);
    onChange(next);
  };

  return (
    <div>
      {links.map((l, i) => (
        <div key={i} className={s.row}>
          <Input className={s.label} value={l.label} placeholder="Label" maxLength={60}
            onChange={(_, d) => set(i, { label: d.value })} aria-label="Link label" />
          <Input className={s.url} value={l.url} placeholder="/sites/... or https://..."
            onChange={(_, d) => set(i, { url: d.value })} aria-label="Link address" />
          <Tooltip content="Move up" relationship="label">
            <Button appearance="subtle" icon={<ArrowUpRegular />} disabled={i === 0} onClick={() => move(i, -1)} />
          </Tooltip>
          <Tooltip content="Move down" relationship="label">
            <Button appearance="subtle" icon={<ArrowDownRegular />} disabled={i === links.length - 1} onClick={() => move(i, 1)} />
          </Tooltip>
          <Tooltip content="Remove" relationship="label">
            <Button appearance="subtle" icon={<DismissRegular />} onClick={() => onChange(links.filter((_, j) => j !== i))} />
          </Tooltip>
        </div>
      ))}
      <div className={s.actions}>
        <Button icon={<AddRegular />} disabled={links.length >= props.max}
          onClick={() => onChange([...links, { label: "", url: "" }])}>
          Add link
        </Button>
        {props.siteContent.length > 0 && (
          <Dropdown
            placeholder="Add a library or list…"
            value=""
            selectedOptions={[]}
            disabled={links.length >= props.max}
            onOptionSelect={(_, d) => {
              const n = props.siteContent.find((c) => c.id === d.optionValue);
              if (n) onChange([...links, { label: n.title ?? n.name, url: n.path }]);
            }}
          >
            {props.siteContent.map((n) => (
              <Option key={n.id} value={n.id} text={n.title ?? n.name}>
                {(n.title ?? n.name) + (n.kind === "LIST" ? " (list)" : " (library)")}
              </Option>
            ))}
          </Dropdown>
        )}
      </div>
    </div>
  );
}
