FDS_Target_OpenLDAP - OpenLDAP custom target for Federated Directory Server
===========================================================================

This project turns an OpenLDAP directory into a custom target for Federated
Directory Server (FDS). Use it to synchronize person and group objects from an
FDS source endpoint - for example an IBM Security Directory Server / IBM
Directory Server - into an OpenLDAP server.

By default the FDS target repository is an IBM Security Directory Server. When a
configuration file whose name is prefixed with FDS_Target is found in the
solution "configs" directory, FDS enables the custom target feature and uses the
custom AssemblyLines and UI forms defined in that file instead.


What is in this project
-----------------------

  FDS_Target_OpenLDAP.xml   The custom target configuration:
                              - AssemblyLines/TestConnection
                              - AssemblyLines/WriteToTarget
                              - Connectors/OpenLDAP        (LDAP library connector)
                              - Connectors/GeneralSettings (Connection Settings UI form)
                              - Connectors/FlowSettings    (Flow Settings UI form)


Supported operations
--------------------

The WriteToTarget AssemblyLine implements the full set of FDS write operations
against OpenLDAP, for both person and group entries:

  $operation     Connector mode   What it does
  -----------    --------------   -----------------------------------------
  initialize     (lazy)           Reported as success; connections are opened
                                  by each operational connector on first use.
  findEntry      Lookup           Looks the entry up by DN (get / lookup). On
                                  no match the Work Entry is emptied so FDS
                                  knows to add it.
  putEntry       Update           Adds the entry (Add).
  modEntry       Update           Modifies the existing entry (Modify). Update
                                  mode with Compute Changes handles both add
                                  and modify and reports "unchanged" when the
                                  entry is already up to date.
  deleteEntry    Delete           Deletes the entry.
  terminate      (lazy)           Reported as success.

TestConnection opens a short-lived LDAP connection using the values on the
Connection Settings page and returns success or the captured error to the FDS
user interface.


Prerequisites
-------------

  1. A running SyncWeave / VDI server with Federated Directory Server, and the
     LDAPSync project deployed (configs/LDAPSync.xml).
  2. Network access from the server to the OpenLDAP host and port.
  3. An OpenLDAP bind account with permission to search, add, modify and delete
     under the target base DN, and the target container(s) already created.


Configuration Editor setup
---------------------------

  1. Import FDS_Target_OpenLDAP.xml into the Configuration Editor.

  2. Confirm the Solution Name is FDS_Target_OpenLDAP (it must start with the
     prefix FDS_Target). Double-click "Solution Logging and Settings", open the
     "Solution Interface" tab, and check the Solution Name field.

  3. Add the reference to the LDAPSync project so its utility functions resolve:
       a. Right-click the References folder of the project.
       b. Select Add New Namespace.
       c. Namespace: LDAPSync
       d. URL:       configs/LDAPSync.xml
       e. Click Verify.

     The AssemblyLines call initUtils(), getProperty(), connectorParam(),
     logmsg() and sendCustomNotification() from this namespace.


Deployment
----------

  1. Copy FDS_Target_OpenLDAP.xml to sdi_solution_dir/configs.
  2. Make sure configs/LDAPSync.xml is present in the same directory.
  3. Restart the server.
  4. Open the Federated Directory Server console (default
     https://localhost:1098/fds).

FDS scans sdi_solution_dir/configs at startup. Because this file name is
prefixed with FDS_Target, the custom target feature is enabled and the
Connection Settings / Flow Settings pages are built from the forms in this
project.


Using the console
-----------------

  1. Click Connection Settings and fill in the OpenLDAP fields:
       - OpenLDAP URL          e.g. ldap://openldap.example.com:389
                               (or ldaps://... with Use SSL selected)
       - Authentication method Simple (or Anonymous)
       - Bind DN               e.g. cn=admin,dc=example,dc=com
       - Bind password
       - Target base DN        e.g. dc=example,dc=com
       - Use SSL               select for ldaps
       - Binary attributes     one per line, if any

  2. Click Test Connection. A green tick means TestConnection succeeded.

  3. Create a flow with your IBM Directory Server source and set the Flow
     Settings:
       - Target person object class  e.g. inetOrgPerson (one class per line)
       - Target user RDN attribute   e.g. uid
       - Target container for users  DN of the users container
                                     (defaults to the target base DN)
       - Target group object class   e.g. groupOfNames
       - Target group RDN attribute  e.g. cn
       - Target container for groups DN of the groups container

  4. Run the flow. Users and groups are created, updated and deleted in
     OpenLDAP as they change in the source.


How the target DN is chosen
---------------------------

FDS maps the source attributes to the target attributes before calling
WriteToTarget. If the Work Entry already carries a $dn, WriteToTarget uses it.
Otherwise it builds the DN from the RDN attribute and container configured in
Flow Settings:

    <rdn attribute>=<value>,<container DN>

for example uid=jdoe,ou=people,dc=example,dc=com. Adjust the object classes,
RDN attributes and containers in Flow Settings to match your OpenLDAP schema
and DIT.


Notes and limitations
---------------------

  - Attribute name and value mapping between the IBM Directory Server source and
    OpenLDAP is performed by FDS. Where the schemas differ (attribute names,
    object classes, group membership attribute such as member vs uniqueMember),
    align them in the flow / map settings.
  - OpenLDAP requires groupOfNames groups to have at least one member. Ensure
    membership is populated, or use a structural class that permits empty
    groups, to avoid object class violations on add.
  - The FlowSettings form here carries the fields needed for an OpenLDAP target.
    To expose the complete set of standard FDS flow settings, copy the
    FlowSettings connector from the LDAPSync project's Resources > Connectors
    library over the one in this project.


Troubleshooting
---------------

  - Console shows the default interface (pass-through authentication,
    write-back) instead of Connection Settings for a custom target:
      * Confirm FDS_Target_OpenLDAP.xml is in sdi_solution_dir/configs.
      * Confirm the project Solution Name starts with FDS_Target.

  - Connection Settings or Flow Settings pages do not appear:
      * Connector and AssemblyLine names are case-sensitive. Verify the
        GeneralSettings and FlowSettings connectors and the TestConnection and
        WriteToTarget AssemblyLines are named exactly as shipped.

  - Person or group entries are not written:
      * Add logmsg() calls in WriteToTarget to confirm it is being invoked and
        which $operation is passed.
      * Confirm the TCB status attributes are being set.
      * Confirm the OpenLDAP target is reachable and the bind account has write
        permission under the target base DN.
