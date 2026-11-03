import { Fragment } from "react";
import { Link } from "react-router-dom";
import { Breadcrumb, BreadcrumbButton, BreadcrumbDivider, BreadcrumbItem } from "@fluentui/react-components";
import { routeFor } from "../api/client";
import type { Crumb } from "../api/types";

export function Crumbs({ items }: { items: Crumb[] }) {
  return (
    <Breadcrumb aria-label="Location" size="large">
      {items.map((c, i) => (
        <Fragment key={c.id}>
          <BreadcrumbItem>
            <Link to={routeFor(c.path)} style={{ textDecoration: "none" }}>
              <BreadcrumbButton current={i === items.length - 1}>{c.title ?? c.name}</BreadcrumbButton>
            </Link>
          </BreadcrumbItem>
          {i < items.length - 1 && <BreadcrumbDivider />}
        </Fragment>
      ))}
    </Breadcrumb>
  );
}
