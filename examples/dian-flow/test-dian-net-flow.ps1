Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ExampleDir = $PSScriptRoot
$RootDir = Resolve-Path (Join-Path $ExampleDir "..\..")
$ProjectPath = Join-Path $RootDir "DIAN_NET\DIAN_NET\DIAN_NET.csproj"
$OutDir = Join-Path $ExampleDir "out"
$BaseUrl = $env:DIAN_NET_TEST_URL
if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    $BaseUrl = "http://localhost:5090"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Wait-DianNet {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Url
    )

    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -Uri "$Url/swagger/index.html" -UseBasicParsing -TimeoutSec 3 | Out-Null
            return
        }
        catch {
            Start-Sleep -Seconds 2
        }
    }

    throw "DIAN_NET no respondió en $Url dentro del tiempo esperado."
}

function Invoke-JsonPost {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Url,

        [Parameter(Mandatory = $true)]
        [string] $Body
    )

    try {
        $response = Invoke-WebRequest `
            -Uri $Url `
            -Method POST `
            -ContentType "application/json; charset=utf-8" `
            -Body $Body `
            -UseBasicParsing `
            -TimeoutSec 60
    }
    catch {
        $errorBody = ""
        if ($_.Exception.Response -and $_.Exception.Response.GetResponseStream()) {
            $reader = [IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            $errorBody = $reader.ReadToEnd()
        }
        throw "POST $Url falló. $errorBody"
    }

    return $response.Content | ConvertFrom-Json
}

function Save-Base64Artifact {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Base64,

        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    [IO.File]::WriteAllBytes($Path, [Convert]::FromBase64String($Base64))
}

$env:ASPNETCORE_URLS = $BaseUrl
$env:ASPNETCORE_ENVIRONMENT = "Development"
$env:DianConfig__Mock__Enabled = "true"
$env:DianConfig__Certificado__Password = "local-mock-certificate-password"

$process = Start-Process `
    -FilePath "dotnet" `
    -ArgumentList "run --project `"$ProjectPath`"" `
    -WorkingDirectory $RootDir `
    -NoNewWindow `
    -PassThru

try {
    Wait-DianNet -Url $BaseUrl

    $invoiceBody = Get-Content -Raw -Path (Join-Path $ExampleDir "invoice.json")
    $invoiceResponse = Invoke-JsonPost -Url "$BaseUrl/api/v1/emit/invoice" -Body $invoiceBody
    $invoiceResponse | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "invoice-response.json")

    if (-not $invoiceResponse.exitoso) {
        throw "Factura rechazada por DIAN_NET: $($invoiceResponse.statusDescription) $($invoiceResponse.statusMessage)"
    }
    if ([string]::IsNullOrWhiteSpace($invoiceResponse.cufe)) {
        throw "La factura no retornó CUFE."
    }
    if ([string]::IsNullOrWhiteSpace($invoiceResponse.signedXmlBase64)) {
        throw "La factura no retornó XML firmado."
    }
    if ([string]::IsNullOrWhiteSpace($invoiceResponse.applicationResponseXmlBase64)) {
        throw "La factura no retornó ApplicationResponse."
    }

    $invoiceSignedXmlPath = Join-Path $OutDir "invoice-signed.xml"
    Save-Base64Artifact -Base64 $invoiceResponse.signedXmlBase64 -Path $invoiceSignedXmlPath
    Save-Base64Artifact -Base64 $invoiceResponse.applicationResponseXmlBase64 -Path (Join-Path $OutDir "invoice-application-response.xml")
    $invoiceSignedXml = Get-Content -Raw -Path $invoiceSignedXmlPath
    if ($invoiceSignedXml -notmatch "<Invoice" -or $invoiceSignedXml -notmatch "TaxTotal" -or $invoiceSignedXml -notmatch "WithholdingTaxTotal") {
        throw "El XML de factura no contiene Invoice, TaxTotal y WithholdingTaxTotal esperados."
    }

    $creditNoteTemplate = Get-Content -Raw -Path (Join-Path $ExampleDir "credit-note.template.json")
    $creditNoteBody = $creditNoteTemplate.Replace("__CUFE_FACTURA__", [string] $invoiceResponse.cufe)
    $creditNoteBody | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "credit-note.json")
    $creditNoteResponse = Invoke-JsonPost -Url "$BaseUrl/api/v1/emit/credit-note" -Body $creditNoteBody
    $creditNoteResponse | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "credit-note-response.json")

    if (-not $creditNoteResponse.exitoso) {
        throw "Nota crédito rechazada por DIAN_NET: $($creditNoteResponse.statusDescription) $($creditNoteResponse.statusMessage)"
    }
    if ([string]::IsNullOrWhiteSpace($creditNoteResponse.cufeCune)) {
        throw "La nota crédito no retornó CUDE/CufeCune."
    }
    if ([string]::IsNullOrWhiteSpace($creditNoteResponse.signedXmlBase64)) {
        throw "La nota crédito no retornó XML firmado."
    }

    $creditNoteSignedXmlPath = Join-Path $OutDir "credit-note-signed.xml"
    Save-Base64Artifact -Base64 $creditNoteResponse.signedXmlBase64 -Path $creditNoteSignedXmlPath
    Save-Base64Artifact -Base64 $creditNoteResponse.applicationResponseXmlBase64 -Path (Join-Path $OutDir "credit-note-application-response.xml")
    $creditNoteSignedXml = Get-Content -Raw -Path $creditNoteSignedXmlPath
    if ($creditNoteSignedXml -notmatch "<CreditNote" -or $creditNoteSignedXml -notmatch "DiscrepancyResponse" -or $creditNoteSignedXml -notmatch "BillingReference" -or $creditNoteSignedXml -notmatch "TaxTotal") {
        throw "El XML de nota crédito no contiene CreditNote, DiscrepancyResponse, BillingReference y TaxTotal esperados."
    }

    [PSCustomObject]@{
        Status = "OK"
        BaseUrl = $BaseUrl
        InvoiceCufe = $invoiceResponse.cufe
        CreditNoteCude = $creditNoteResponse.cufeCune
        OutputDir = $OutDir
    } | ConvertTo-Json -Depth 5
}
finally {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
}
