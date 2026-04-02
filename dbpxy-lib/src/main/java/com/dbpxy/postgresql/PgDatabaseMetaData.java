package com.dbpxy.postgresql;

/*-
 * #%L
 * dbpxy-lib
 * %%
 * Copyright (C) 2025 Fernando Lemes Povoa
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.dbpxy.jdbc.Connection;
import com.dbpxy.jdbc.Statement;
import com.google.common.primitives.Ints;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Optional;
import java.util.Properties;

@Slf4j
@RequiredArgsConstructor
public class PgDatabaseMetaData implements java.sql.DatabaseMetaData {
    private static String version = "UNSET";
    private static int majorVersion = 0;
    private static int minorVersion = 0;

    static {
        final Properties prop = new Properties();
        try (final InputStream input = PgDatabaseMetaData.class.getClassLoader().getResourceAsStream("dbpxy.properties")) {
            prop.load(input);
            version = prop.getProperty("version");
            majorVersion = Optional.ofNullable(Ints.tryParse(prop.getProperty("majorVersion"))).orElse(0);
            minorVersion = Optional.ofNullable(Ints.tryParse(prop.getProperty("minorVersion"))).orElse(0);
        } catch (final IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    private final Connection connection;
    private String keywords;

    @Override
    public boolean allProceduresAreCallable() {
        return true;
    }

    @Override
    public boolean allTablesAreSelectable() {
        return true;
    }

    @Override
    public String getURL() {
        return "";
    }

    @Override
    public String getUserName() {
        return "";
    }

    @Override
    public boolean isReadOnly() {
        return connection.isReadOnly();
    }

    @Override
    public boolean nullsAreSortedHigh() {
        return true;
    }

    @Override
    public boolean nullsAreSortedLow() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return false;
    }

    @Override
    public String getDatabaseProductName() {
        return "PostgreSQL";
    }

    @Override
    public String getDatabaseProductVersion() {
        return "";
    }

    @Override
    public String getDriverName() {
        return "DBPXY JDBC Driver for PostgreSQL";
    }

    @Override
    public String getDriverVersion() {
        return version;
    }

    @Override
    public int getDriverMajorVersion() {
        return majorVersion;
    }

    @Override
    public int getDriverMinorVersion() {
        return minorVersion;
    }

    @Override
    public boolean usesLocalFiles() {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return false;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public String getIdentifierQuoteString() {
        return "\"";
    }

    @Override
    public String getSQLKeywords() throws SQLException {
        String keywords = this.keywords;
        if (keywords == null) {
            // Exclude SQL:2003 keywords (https://github.com/ronsavage/SQL/blob/master/sql-2003-2.bnf)
            // from the returned list, ugly but required by jdbc spec.
            String sql = "select string_agg(word, ',') from pg_catalog.pg_get_keywords() "
                    + "where word <> ALL ('{a,abs,absolute,action,ada,add,admin,after,all,allocate,alter,"
                    + "always,and,any,are,array,as,asc,asensitive,assertion,assignment,asymmetric,at,atomic,"
                    + "attribute,attributes,authorization,avg,before,begin,bernoulli,between,bigint,binary,"
                    + "blob,boolean,both,breadth,by,c,call,called,cardinality,cascade,cascaded,case,cast,"
                    + "catalog,catalog_name,ceil,ceiling,chain,char,char_length,character,character_length,"
                    + "character_set_catalog,character_set_name,character_set_schema,characteristics,"
                    + "characters,check,checked,class_origin,clob,close,coalesce,cobol,code_units,collate,"
                    + "collation,collation_catalog,collation_name,collation_schema,collect,column,"
                    + "column_name,command_function,command_function_code,commit,committed,condition,"
                    + "condition_number,connect,connection_name,constraint,constraint_catalog,constraint_name,"
                    + "constraint_schema,constraints,constructors,contains,continue,convert,corr,"
                    + "corresponding,count,covar_pop,covar_samp,create,cross,cube,cume_dist,current,"
                    + "current_collation,current_date,current_default_transform_group,current_path,"
                    + "current_role,current_time,current_timestamp,current_transform_group_for_type,current_user,"
                    + "cursor,cursor_name,cycle,data,date,datetime_interval_code,datetime_interval_precision,"
                    + "day,deallocate,dec,decimal,declare,default,defaults,deferrable,deferred,defined,definer,"
                    + "degree,delete,dense_rank,depth,deref,derived,desc,describe,descriptor,deterministic,"
                    + "diagnostics,disconnect,dispatch,distinct,domain,double,drop,dynamic,dynamic_function,"
                    + "dynamic_function_code,each,element,else,end,end-exec,equals,escape,every,except,"
                    + "exception,exclude,excluding,exec,execute,exists,exp,external,extract,false,fetch,filter,"
                    + "final,first,float,floor,following,for,foreign,fortran,found,free,from,full,function,"
                    + "fusion,g,general,get,global,go,goto,grant,granted,group,grouping,having,hierarchy,hold,"
                    + "hour,identity,immediate,implementation,in,including,increment,indicator,initially,"
                    + "inner,inout,input,insensitive,insert,instance,instantiable,int,integer,intersect,"
                    + "intersection,interval,into,invoker,is,isolation,join,k,key,key_member,key_type,language,"
                    + "large,last,lateral,leading,left,length,level,like,ln,local,localtime,localtimestamp,"
                    + "locator,lower,m,map,match,matched,max,maxvalue,member,merge,message_length,"
                    + "message_octet_length,message_text,method,min,minute,minvalue,mod,modifies,module,month,"
                    + "more,multiset,mumps,name,names,national,natural,nchar,nclob,nesting,new,next,no,none,"
                    + "normalize,normalized,not,\"null\",nullable,nullif,nulls,number,numeric,object,"
                    + "octet_length,octets,of,old,on,only,open,option,options,or,order,ordering,ordinality,"
                    + "others,out,outer,output,over,overlaps,overlay,overriding,pad,parameter,parameter_mode,"
                    + "parameter_name,parameter_ordinal_position,parameter_specific_catalog,"
                    + "parameter_specific_name,parameter_specific_schema,partial,partition,pascal,path,"
                    + "percent_rank,percentile_cont,percentile_disc,placing,pli,position,power,preceding,"
                    + "precision,prepare,preserve,primary,prior,privileges,procedure,public,range,rank,read,"
                    + "reads,real,recursive,ref,references,referencing,regr_avgx,regr_avgy,regr_count,"
                    + "regr_intercept,regr_r2,regr_slope,regr_sxx,regr_sxy,regr_syy,relative,release,"
                    + "repeatable,restart,result,return,returned_cardinality,returned_length,"
                    + "returned_octet_length,returned_sqlstate,returns,revoke,right,role,rollback,rollup,"
                    + "routine,routine_catalog,routine_name,routine_schema,row,row_count,row_number,rows,"
                    + "savepoint,scale,schema,schema_name,scope_catalog,scope_name,scope_schema,scroll,"
                    + "search,second,section,security,select,self,sensitive,sequence,serializable,server_name,"
                    + "session,session_user,set,sets,similar,simple,size,smallint,some,source,space,specific,"
                    + "specific_name,specifictype,sql,sqlexception,sqlstate,sqlwarning,sqrt,start,state,"
                    + "statement,static,stddev_pop,stddev_samp,structure,style,subclass_origin,submultiset,"
                    + "substring,sum,symmetric,system,system_user,table,table_name,tablesample,temporary,then,"
                    + "ties,time,timestamp,timezone_hour,timezone_minute,to,top_level_count,trailing,"
                    + "transaction,transaction_active,transactions_committed,transactions_rolled_back,"
                    + "transform,transforms,translate,translation,treat,trigger,trigger_catalog,trigger_name,"
                    + "trigger_schema,trim,true,type,uescape,unbounded,uncommitted,under,union,unique,unknown,"
                    + "unnamed,unnest,update,upper,usage,user,user_defined_type_catalog,user_defined_type_code,"
                    + "user_defined_type_name,user_defined_type_schema,using,value,values,var_pop,var_samp,"
                    + "varchar,varying,view,when,whenever,where,width_bucket,window,with,within,without,work,"
                    + "write,year,zone}'::text[])";

            try (final java.sql.Statement stmt = connection.createStatement();
                 final ResultSet rs = stmt.executeQuery(sql);) {

                if (!rs.next()) {
                    throw new SQLException("Unable to find keywords in the system catalogs.");
                }
                keywords = rs.getString(1);
            }

            this.keywords = keywords;
        }
        return keywords;
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getNumericFunctions() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getStringFunctions() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getSystemFunctions() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getTimeDateFunctions() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public String getSearchStringEscape() {
        // This method originally returned "\\\\" assuming that it
        // would be fed directly into pg's input parser so it would
        // need two backslashes. This isn't how it's supposed to be
        // used though. If passed as a PreparedStatement parameter
        // or fed to a DatabaseMetaData method then double backslashes
        // are incorrect. If you're feeding something directly into
        // a query you are responsible for correctly escaping it.
        // With 8.2+ this escaping is a little trickier because you
        // must know the setting of standard_conforming_strings, but
        // that's not our problem.

        return "\\";
    }

    @Override
    public String getExtraNameCharacters() {
        return "";
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() {
        return true;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return true;
    }

    @Override
    public boolean supportsConvert() {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() {
        return true;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return true;
    }

    @Override
    public boolean supportsLikeEscapeClause() {
        return true;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return true;
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return true;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return true;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() {
        return true;
    }

    @Override
    public boolean supportsOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return true;
    }

    @Override
    public String getSchemaTerm() {
        return "schema";
    }

    @Override
    public String getProcedureTerm() {
        return "function";
    }

    @Override
    public String getCatalogTerm() {
        return "database";
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public String getCatalogSeparator() {
        return ".";
    }

    @Override
    public boolean supportsSchemasInDataManipulation() {
        return true;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return true;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return true;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return true;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return true;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() {
        return false; // For now...
    }

    @Override
    public boolean supportsPositionedUpdate() {
        return false; // For now...
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return true;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return true;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return true;
    }

    @Override
    public boolean supportsUnion() {
        return true; // since 6.3
    }

    @Override
    public boolean supportsUnionAll() {
        return true;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return true;
    }

    @Override
    public int getMaxCharLiteralLength() {
        return 0; // no limit
    }

    @Override
    public int getMaxBinaryLiteralLength() {
        return 0; // no limit
    }

    @Override
    public int getMaxColumnNameLength() {
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() {
        return 0; // no limit
    }

    @Override
    public int getMaxColumnsInIndex() {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() {
        return 0; // no limit
    }

    @Override
    public int getMaxColumnsInSelect() {
        return 0; // no limit
    }

    @Override
    public int getMaxColumnsInTable() {
        return 1600;
    }

    @Override
    public int getMaxConnections() {
        return 8192;
    }

    @Override
    public int getMaxCursorNameLength() {
        return 0;
    }

    @Override
    public int getMaxIndexLength() {
        return 0; // no limit (larger than an int anyway)
    }

    @Override
    public int getMaxSchemaNameLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() {
        return 0;
    }

    @Override
    public int getMaxRowSize() {
        return 1073741824; // 1 GB
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return false;
    }

    @Override
    public int getMaxStatementLength() {
        return 0; // actually whatever fits in size_t
    }

    @Override
    public int getMaxStatements() {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() {
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 0; // no limit
    }

    @Override
    public int getMaxUserNameLength() {
        return 0;
    }

    @Override
    public int getDefaultTransactionIsolation() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean supportsTransactions() {
        return true;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return switch (level) {
            case java.sql.Connection.TRANSACTION_READ_UNCOMMITTED,
                 java.sql.Connection.TRANSACTION_READ_COMMITTED,
                 java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                 java.sql.Connection.TRANSACTION_SERIALIZABLE -> true;
            default -> false;
        };
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() {
        return true;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() {
        return false;
    }

    @Override
    public java.sql.ResultSet getProcedures(String catalog, String schemaPattern,
                                            String procedureNamePattern)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getProcedureColumns(String catalog, String schemaPattern,
                                                  String procedureNamePattern, String columnNamePattern)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getTables(String catalog, String schemaPattern,
                                        String tableNamePattern, String[] types) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    @Override
    public java.sql.ResultSet getSchemas(String catalog, String schemaPattern)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getCatalogs() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getTableTypes() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getColumns(String catalog, String schemaPattern,
                                         String tableNamePattern,
                                         String columnNamePattern) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getColumnPrivileges(String catalog, String schema,
                                                  String table, String columnNamePattern) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getTablePrivileges(String catalog, String schemaPattern,
                                                 String tableNamePattern) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getBestRowIdentifier(
            String catalog, String schema, String table,
            int scope, boolean nullable) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getVersionColumns(
            String catalog, String schema, String table)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getPrimaryKeys(String catalog, String schema, String table)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getImportedKeys(String catalog, String schema, String table)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getExportedKeys(String catalog, String schema, String table)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getCrossReference(
            String primaryCatalog, String primarySchema, String primaryTable,
            String foreignCatalog, String foreignSchema, String foreignTable)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getTypeInfo() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getIndexInfo(
            String catalog, String schema, String tableName,
            boolean unique, boolean approximate) throws SQLException {
        throw new SQLException("not implemented");
    }

    // ** JDBC 2 Extensions **

    @Override
    public boolean supportsResultSetType(int type) {
        // The only type we don't support
        return type != java.sql.ResultSet.TYPE_SCROLL_SENSITIVE;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        // These combinations are not supported!
        if (type == java.sql.ResultSet.TYPE_SCROLL_SENSITIVE) {
            return false;
        }

        // We do support Updateable ResultSets
        if (concurrency == java.sql.ResultSet.CONCUR_UPDATABLE) {
            return true;
        }

        // Everything else we do
        return true;
    }

    /* lots of unsupported stuff... */
    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return true;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return true;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        // indicates that
        return true;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int i) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int i) {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() {
        return true;
    }

    @Override
    public java.sql.ResultSet getUDTs(String catalog, String schemaPattern,
                                      String typeNamePattern, int[] types) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.Connection getConnection() {
        return connection;
    }

    protected Statement createMetaDataStatement() throws SQLException {
        return connection.createStatement(java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE,
                java.sql.ResultSet.CONCUR_READ_ONLY);
    }

    @Override
    public long getMaxLogicalLobSize() {
        return 0;
    }

    @Override
    public boolean supportsRefCursors() {
        return true;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        throw new SQLException("getRowIdLifetime()");
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return true;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    @Override
    public java.sql.ResultSet getClientInfoProperties() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
        return null;
    }

    @Override
    public java.sql.ResultSet getFunctions(String catalog, String schemaPattern,
                                           String functionNamePattern)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getFunctionColumns(String catalog, String schemaPattern,
                                                 String functionNamePattern, String columnNamePattern)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public java.sql.ResultSet getPseudoColumns(String catalog, String schemaPattern,
                                               String tableNamePattern, String columnNamePattern)
            throws SQLException {
        throw new SQLException(
                "getPseudoColumns(String, String, String, String)");
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return true;
    }

    @Override
    public boolean supportsSavepoints() {
        return true;
    }

    @Override
    public boolean supportsNamedParameters() {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        // We don't support returning generated keys by column index,
        // but that should be a rarer case than the ones we do support.
        //
        return true;
    }

    @Override
    public java.sql.ResultSet getSuperTypes(String catalog, String schemaPattern,
                                            String typeNamePattern)
            throws SQLException {
        throw new SQLException(
                "getSuperTypes(String,String,String)");
    }

    @Override
    public java.sql.ResultSet getSuperTables(String catalog, String schemaPattern,
                                             String tableNamePattern)
            throws SQLException {
        throw new SQLException(
                "getSuperTables(String,String,String,String)");
    }

    @Override
    public java.sql.ResultSet getAttributes(String catalog, String schemaPattern,
                                            String typeNamePattern, String attributeNamePattern) throws SQLException {
        throw new SQLException(
                "getAttributes(String,String,String,String)");
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return true;
    }

    @Override
    public int getResultSetHoldability() {
//        return java.sql.ResultSet.HOLD_CURSORS_OVER_COMMIT;
        return java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getDatabaseMajorVersion() {
        return 0;
    }

    @Override
    public int getDatabaseMinorVersion() {
        return 0;
    }

    @Override
    public int getJDBCMajorVersion() {
        return 0;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 0;
    }

    @Override
    public int getSQLStateType() {
        return sqlStateSQL;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        /*
         * Currently LOBs aren't updateable at all, so it doesn't matter what we return. We don't throw
         * the notImplemented Exception because the 1.5 JDK's CachedRowSet calls this method regardless
         * of whether large objects are used.
         */
        return true;
    }

    @Override
    public boolean supportsStatementPooling() {
        return false;
    }
}
