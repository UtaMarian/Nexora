<#
.SYNOPSIS
  Registers NEXORA (through its Keycloak broker) as a relying party trust in AD FS.

.DESCRIPTION
  Run on an AD FS server as a domain/AD FS administrator. NEXORA's Keycloak acts as the SAML 2.0 service
  provider; AD FS authenticates users (Windows integrated auth, MFA, ...) and sends:
    - UPN (also used as NameID), e-mail, given name, surname
    - group names (Token-Groups, unqualified) -> NEXORA directory groups, usable in site permissions

  Afterwards set ADFS_METADATA_URL (and NEXORA_ADFS_BUTTON_ALIAS=adfs) in deploy/.env and re-run
  "docker compose up -d keycloak-provision backend".

.EXAMPLE
  .\Register-NexoraRelyingParty.ps1 -NexoraUrl https://nexora.corp.example.com

.EXAMPLE
  # AD FS cannot reach NEXORA (or does not trust its certificate yet): use a downloaded metadata file
  .\Register-NexoraRelyingParty.ps1 -NexoraUrl https://nexora.corp.example.com -MetadataFile .\nexora-sp.xml
#>
param(
    [Parameter(Mandatory = $true)] [string] $NexoraUrl,
    [string] $Name = "NEXORA",
    [string] $Realm = "nexora",
    [string] $Alias = "adfs",
    [string] $MetadataFile,
    [string] $AccessControlPolicy = "Permit everyone"
)
$ErrorActionPreference = "Stop"
Import-Module ADFS

$NexoraUrl = $NexoraUrl.TrimEnd("/")
$metadataUrl = "$NexoraUrl/auth/realms/$Realm/broker/$Alias/endpoint/descriptor"

$rules = @'
@RuleTemplate = "LdapClaims"
@RuleName = "Send UPN, e-mail, names and groups"
c:[Type == "http://schemas.microsoft.com/ws/2008/06/identity/claims/windowsaccountname", Issuer == "AD AUTHORITY"]
 => issue(store = "Active Directory",
          types = ("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn",
                   "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
                   "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/givenname",
                   "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/surname",
                   "http://schemas.xmlsoap.org/claims/Group"),
          query = ";userPrincipalName,mail,givenName,sn,tokenGroups;{0}", param = c.Value);

@RuleTemplate = "MapClaims"
@RuleName = "UPN as NameID"
c:[Type == "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn"]
 => issue(Type = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier",
          Issuer = c.Issuer, OriginalIssuer = c.OriginalIssuer, Value = c.Value, ValueType = c.ValueType,
          Properties["http://schemas.xmlsoap.org/ws/2005/05/identity/claimproperties/format"] = "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified");
'@

$common = @{
    Name                       = $Name
    IssuanceTransformRules     = $rules
    AccessControlPolicyName    = $AccessControlPolicy
    SignatureAlgorithm         = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"
    MonitoringEnabled          = $true
    AutoUpdateEnabled          = $true
}

if (Get-AdfsRelyingPartyTrust -Name $Name -ErrorAction SilentlyContinue) {
    Write-Host "Relying party '$Name' exists: updating claim rules"
    Set-AdfsRelyingPartyTrust -TargetName $Name -IssuanceTransformRules $rules -SignatureAlgorithm $common.SignatureAlgorithm
} elseif ($MetadataFile) {
    Add-AdfsRelyingPartyTrust @common -MetadataFile $MetadataFile
} else {
    Add-AdfsRelyingPartyTrust @common -MetadataUrl $metadataUrl
}

$fed = (Get-AdfsProperties).HostName
Write-Host ""
Write-Host "Done. In NEXORA's deploy/.env set:"
Write-Host "  ADFS_METADATA_URL=https://$fed/FederationMetadata/2007-06/FederationMetadata.xml"
Write-Host "  NEXORA_ADFS_BUTTON_ALIAS=$Alias"
Write-Host "then: docker compose up -d keycloak-provision backend"
