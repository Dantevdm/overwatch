#!/usr/bin/env python3
"""
Compare the JPA entity mappings against the schema Flyway actually builds.

Hibernate runs with ddl-auto: validate, so any disagreement between an entity
and the migration stops the service at startup. That is the correct behaviour,
but discovering it by starting the stack is a slow way to learn: the build takes
minutes and reports one mismatched column at a time.

This does the same comparison statically, against a real PostgreSQL, and reports
every mismatch at once.

    ./scripts/verify-schema-mapping.py --dsn postgresql://overwatch@localhost/overwatch

Exits non-zero on any mismatch, so it works as a CI gate.
"""
from __future__ import annotations

import argparse
import glob
import re
import sys

ENTITY_GLOB = "common/src/main/java/com/overwatch/common/persistence/*.java"

# How Hibernate maps a Java field to a PostgreSQL type, given its annotations.
def expected_type(java_type: str, ann: dict) -> str | None:
    if "columnDefinition" in ann:
        return ann["columnDefinition"].lower().strip()
    if ann.get("jdbcTypeCode") == "JSON":
        return "jsonb"
    if java_type == "String":
        return f"character varying({ann['length']})" if "length" in ann \
               else "character varying(255)"
    if java_type == "BigDecimal":
        if "precision" in ann and "scale" in ann:
            return f"numeric({ann['precision']},{ann['scale']})"
        return "numeric"
    if java_type == "Instant":
        return "timestamp with time zone"
    if java_type == "UUID":
        return "uuid"
    if java_type == "Long":
        return "bigint"
    if java_type in ("Integer", "int"):
        return "integer"
    if java_type in ("Boolean", "boolean"):
        return "boolean"
    return None          # a type this check does not model; skipped, not guessed


def camel_to_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def parse_entities() -> dict[str, dict[str, tuple[str, dict]]]:
    """table -> {column: (java_type, annotation_values)}"""
    tables: dict[str, dict[str, tuple[str, dict]]] = {}
    for path in sorted(glob.glob(ENTITY_GLOB)):
        src = open(path).read()
        m = re.search(r'@Table\(name\s*=\s*"(\w+)"\)', src)
        if not m:
            continue
        table, columns = m.group(1), {}

        # Each field, with whatever annotations precede it.
        for fm in re.finditer(
            r'((?:\s*@[\w.]+(?:\([^)]*\))?\s*\n)*)\s*private\s+([\w<>, .]+?)\s+(\w+);', src
        ):
            annotations, java_type, field = fm.group(1), fm.group(2).strip(), fm.group(3)
            if "@Column" not in annotations and "@JoinColumn" not in annotations \
                    and "@Id" not in annotations:
                continue
            if "@OneToMany" in annotations or "@ManyToOne" in annotations:
                # Association: the FK column is checked from the owning side's
                # @JoinColumn, and its type comes from the target's id.
                jm = re.search(r'@JoinColumn\(name\s*=\s*"(\w+)"', annotations)
                if jm:
                    columns[jm.group(1)] = ("UUID", {})
                continue

            ann: dict = {}
            cm = re.search(r'@Column\(([^)]*)\)', annotations)
            column = camel_to_snake(field)
            if cm:
                body = cm.group(1)
                nm = re.search(r'name\s*=\s*"(\w+)"', body)
                if nm:
                    column = nm.group(1)
                for key in ("length", "precision", "scale"):
                    km = re.search(rf'{key}\s*=\s*(\d+)', body)
                    if km:
                        ann[key] = km.group(1)
                dm = re.search(r'columnDefinition\s*=\s*"([^"]+)"', body)
                if dm:
                    ann["columnDefinition"] = dm.group(1)
            if "SqlTypes.JSON" in annotations:
                ann["jdbcTypeCode"] = "JSON"

            # Normalise the declared Java type to its simple name.
            simple = java_type.split("<")[0].split(".")[-1].strip()
            if simple == "Map":
                simple = "Map"
            columns[column] = (simple, ann)
        tables[table] = columns
    return tables


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", required=True)
    args = ap.parse_args()

    try:
        import psycopg2
    except ImportError:
        print("psycopg2 not installed: pip install psycopg2-binary", file=sys.stderr)
        return 2

    entities = parse_entities()
    conn = psycopg2.connect(args.dsn)
    cur = conn.cursor()

    problems, checked = [], 0
    for table, columns in sorted(entities.items()):
        cur.execute("""
            SELECT column_name, data_type, character_maximum_length,
                   numeric_precision, numeric_scale, udt_name
            FROM information_schema.columns WHERE table_name = %s
        """, (table,))
        actual = {r[0]: r for r in cur.fetchall()}
        if not actual:
            problems.append(f"{table}: table does not exist in the schema")
            continue

        for column, (java_type, ann) in sorted(columns.items()):
            if column not in actual:
                problems.append(f"{table}.{column}: entity declares it, schema does not have it")
                continue
            want = expected_type(java_type, ann)
            if want is None:
                continue
            _, data_type, charlen, prec, scale, udt = actual[column]
            if data_type == "character varying":
                got = f"character varying({charlen})" if charlen else "character varying"
            elif data_type == "numeric":
                got = f"numeric({prec},{scale})" if prec else "numeric"
            elif data_type == "USER-DEFINED":
                got = udt
            elif data_type == "character":
                got = f"character({charlen})"
            else:
                got = data_type
            checked += 1
            if got != want:
                problems.append(
                    f"{table}.{column}: entity expects [{want}], schema has [{got}]")

    print(f"Checked {checked} columns across {len(entities)} entities.\n")
    if problems:
        for p in problems:
            print(f"  MISMATCH  {p}")
        print(f"\n{len(problems)} mismatch(es) — ddl-auto: validate would refuse to start.")
        return 1
    print("  Every entity column matches the schema Flyway builds.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
