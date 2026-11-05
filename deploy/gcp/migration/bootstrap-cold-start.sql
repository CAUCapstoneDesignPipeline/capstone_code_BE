\set ON_ERROR_STOP on
BEGIN;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '2s';
SET LOCAL search_path = pg_catalog;
DO $guard$
BEGIN
    IF current_database() <> 'capstone_live'
       OR EXISTS (SELECT FROM pg_roles WHERE rolname = 'capstone_live_app')
       OR EXISTS (SELECT FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname NOT IN ('information_schema','public'))
       OR EXISTS (SELECT FROM pg_class WHERE relnamespace = 'public'::regnamespace)
       OR EXISTS (SELECT FROM pg_proc WHERE pronamespace = 'public'::regnamespace)
       OR EXISTS (SELECT FROM pg_type WHERE typnamespace = 'public'::regnamespace)
       OR EXISTS (SELECT FROM pg_depend WHERE refclassid = 'pg_namespace'::regclass
                  AND refobjid = 'public'::regnamespace AND deptype = 'n')
       OR EXISTS (SELECT FROM pg_extension WHERE extname <> 'plpgsql')
       OR EXISTS (SELECT FROM pg_largeobject_metadata)
    THEN RAISE EXCEPTION 'Cold start requires the fresh target and a new role';
    END IF;
END $guard$;
CREATE ROLE capstone_live_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'scram';
REVOKE CONNECT, TEMPORARY ON DATABASE capstone_live FROM PUBLIC;
GRANT CONNECT ON DATABASE capstone_live TO capstone_live_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO capstone_live_app;
DO $verify$
BEGIN
    IF NOT has_database_privilege('capstone_live_app','capstone_live','CONNECT')
       OR has_database_privilege('capstone_live_app','capstone_live','CREATE,TEMP')
       OR NOT has_schema_privilege('capstone_live_app','public','CREATE')
       OR NOT has_schema_privilege('capstone_live_app','public','USAGE')
       OR EXISTS (SELECT FROM pg_database d, LATERAL aclexplode(coalesce(d.datacl, acldefault('d',d.datdba))) a
                  WHERE d.datname=current_database() AND a.grantee=0 AND a.privilege_type IN ('CONNECT','TEMPORARY'))
    THEN RAISE EXCEPTION 'Cold-start privileges mismatch';
    END IF;
END $verify$;
COMMIT;
