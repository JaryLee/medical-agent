DO $$
DECLARE
    tenant_table RECORD;
    index_name TEXT;
BEGIN
    FOR tenant_table IN
        SELECT table_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND column_name IN ('id', 'hospital_id')
        GROUP BY table_name
        HAVING count(DISTINCT column_name) = 2
        ORDER BY table_name
    LOOP
        index_name := 'uk_tenant_' || substr(md5(tenant_table.table_name), 1, 16);
        EXECUTE format(
            'CREATE UNIQUE INDEX IF NOT EXISTS %I ON public.%I(hospital_id, id)',
            index_name,
            tenant_table.table_name
        );
    END LOOP;
END
$$;

DO $$
DECLARE
    relation RECORD;
    constraint_name TEXT;
    delete_action TEXT;
BEGIN
    FOR relation IN
        SELECT
            child.relname AS child_table,
            child_column.attname AS child_column,
            parent.relname AS parent_table,
            constraint_row.confdeltype AS delete_type
        FROM pg_constraint constraint_row
        JOIN pg_class child
          ON child.oid = constraint_row.conrelid
        JOIN pg_namespace child_namespace
          ON child_namespace.oid = child.relnamespace
        JOIN pg_class parent
          ON parent.oid = constraint_row.confrelid
        JOIN LATERAL unnest(constraint_row.conkey)
             WITH ORDINALITY child_key(attnum, position) ON TRUE
        JOIN LATERAL unnest(constraint_row.confkey)
             WITH ORDINALITY parent_key(attnum, position)
          ON parent_key.position = child_key.position
        JOIN pg_attribute child_column
          ON child_column.attrelid = child.oid
         AND child_column.attnum = child_key.attnum
        JOIN pg_attribute parent_column
          ON parent_column.attrelid = parent.oid
         AND parent_column.attnum = parent_key.attnum
        WHERE constraint_row.contype = 'f'
          AND child_namespace.nspname = 'public'
          AND cardinality(constraint_row.conkey) = 1
          AND child_column.attname <> 'hospital_id'
          AND parent_column.attname = 'id'
          AND EXISTS (
              SELECT 1
              FROM pg_attribute attribute_row
              WHERE attribute_row.attrelid = child.oid
                AND attribute_row.attname = 'hospital_id'
                AND NOT attribute_row.attisdropped
          )
          AND EXISTS (
              SELECT 1
              FROM pg_attribute attribute_row
              WHERE attribute_row.attrelid = parent.oid
                AND attribute_row.attname = 'hospital_id'
                AND NOT attribute_row.attisdropped
          )
        ORDER BY child.relname, child_column.attname, parent.relname
    LOOP
        constraint_name := 'fk_tenant_'
            || substr(md5(
                relation.child_table || ':' || relation.child_column
                || ':' || relation.parent_table
            ), 1, 20);
        delete_action := CASE relation.delete_type
            WHEN 'c' THEN ' CASCADE'
            WHEN 'r' THEN ' RESTRICT'
            ELSE ' NO ACTION'
        END;

        EXECUTE format(
            'ALTER TABLE public.%I ADD CONSTRAINT %I '
            || 'FOREIGN KEY (hospital_id, %I) '
            || 'REFERENCES public.%I(hospital_id, id) '
            || 'ON DELETE%s NOT VALID',
            relation.child_table,
            constraint_name,
            relation.child_column,
            relation.parent_table,
            delete_action
        );

        EXECUTE format(
            'ALTER TABLE public.%I VALIDATE CONSTRAINT %I',
            relation.child_table,
            constraint_name
        );
    END LOOP;
END
$$;
