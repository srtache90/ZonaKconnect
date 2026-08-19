Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ExampleDir = $PSScriptRoot
$OutDir = Join-Path $ExampleDir "out-core-go"
$BaseUrl = if ($env:CORE_GO_URL) { $env:CORE_GO_URL } else { "http://localhost:8081" }
$TenantId = "00000000-0000-0000-0000-000000000001"
$EmissionPointId = "00000000-0000-0000-0000-000000000101"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-CoreGo {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Method,
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [string] $Body = $null
    )

    $headers = @{
        "X-Tenant-ID" = $TenantId
        "X-Emission-Point-ID" = $EmissionPointId
    }
    if ($Body) {
        $headers["Content-Type"] = "application/json; charset=utf-8"
    }

    $params = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $headers
        UseBasicParsing = $true
        TimeoutSec = 60
    }
    if ($Body) {
        $params.Body = $Body
    }

    try {
        return Invoke-WebRequest @params
    }
    catch {
        $errorBody = ""
        if ($_.Exception.Response -and $_.Exception.Response.GetResponseStream()) {
            $reader = [IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            $errorBody = $reader.ReadToEnd()
        }
        throw "$Method $Path falló. $errorBody"
    }
}

function Wait-InvoiceReady {
    param(
        [Parameter(Mandatory = $true)]
        [string] $InvoiceId,
        [int] $MaxSeconds = 45
    )

    $deadline = (Get-Date).AddSeconds($MaxSeconds)
    while ((Get-Date) -lt $deadline) {
        $response = Invoke-CoreGo -Method GET -Path "/api/v1/invoices?limit=50"
        $payload = $response.Content | ConvertFrom-Json
        $invoice = $payload.invoices | Where-Object { $_.id -eq $InvoiceId } | Select-Object -First 1
        if ($invoice -and $invoice.estado_dian -notin @("PENDIENTE", "PENDIENTE_NC")) {
            return $invoice
        }
        Start-Sleep -Seconds 2
    }

    throw "La factura $InvoiceId no terminó de procesarse en $MaxSeconds segundos."
}

function Save-Document {
    param(
        [Parameter(Mandatory = $true)]
        [string] $InvoiceId,
        [Parameter(Mandatory = $true)]
        [string] $Kind,
        [Parameter(Mandatory = $true)]
        [string] $FileName
    )

    $response = Invoke-CoreGo -Method GET -Path "/api/v1/invoices/$InvoiceId/documents/$Kind"
    $path = Join-Path $OutDir $FileName
    [IO.File]::WriteAllBytes($path, $response.RawContentStream.ToArray())
    if ((Get-Item $path).Length -eq 0) {
        throw "Documento vacío: $FileName"
    }
    return $path
}

# 1. Crear factura
$invoiceBody = Get-Content -Raw -Path (Join-Path $ExampleDir "core-invoice.json")
$createInvoice = Invoke-CoreGo -Method POST -Path "/api/v1/invoices" -Body $invoiceBody
$invoiceCreated = $createInvoice.Content | ConvertFrom-Json
$invoiceId = [string] $invoiceCreated.id
if (-not $invoiceId) {
    throw "Core Go no retornó id de factura."
}

Write-Host "Factura creada: $invoiceId" -ForegroundColor Cyan
$invoice = Wait-InvoiceReady -InvoiceId $invoiceId
$invoice | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "invoice-db.json")

if (-not $invoice.uuid_cude) {
    throw "Factura sin CUFE/CUDE en base de datos. estado_dian=$($invoice.estado_dian)"
}

$invoiceNumber = "$($invoice.prefijo)$($invoice.numero)"
Write-Host "Factura emitida: $invoiceNumber CUFE=$($invoice.uuid_cude) estado=$($invoice.estado_dian)" -ForegroundColor Green

# 2. Descargar documentos de factura
Save-Document -InvoiceId $invoiceId -Kind "signed-xml" -FileName "core-invoice-signed.xml" | Out-Null
Save-Document -InvoiceId $invoiceId -Kind "app-response" -FileName "core-invoice-app-response.xml" | Out-Null
Save-Document -InvoiceId $invoiceId -Kind "pdf" -FileName "core-invoice.pdf" | Out-Null

$invoiceXml = Get-Content -Raw -Path (Join-Path $OutDir "core-invoice-signed.xml")
if ($invoiceXml -notmatch "<Invoice" -or $invoiceXml -notmatch "TaxTotal") {
    throw "XML firmado de factura inválido."
}

# 3. Crear nota crédito referenciando factura
$creditTemplate = Get-Content -Raw -Path (Join-Path $ExampleDir "core-credit-note.template.json")
$creditBody = $creditTemplate.Replace("__CUFE_FACTURA__", [string] $invoice.uuid_cude).Replace("__NUMERO_FACTURA__", $invoiceNumber)
$creditBody | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "core-credit-note.json")

$createCredit = Invoke-CoreGo -Method POST -Path "/api/v1/credit-notes" -Body $creditBody
$creditCreated = $createCredit.Content | ConvertFrom-Json
$creditId = [string] $creditCreated.id
if (-not $creditId) {
    throw "Core Go no retornó id de nota crédito."
}

Write-Host "Nota crédito creada: $creditId" -ForegroundColor Cyan
$creditNote = Wait-InvoiceReady -InvoiceId $creditId
$creditNote | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "credit-note-db.json")

if (-not $creditNote.uuid_cude) {
    throw "Nota crédito sin CUDE. estado_dian=$($creditNote.estado_dian)"
}

Write-Host "Nota crédito emitida: $($creditNote.prefijo)$($creditNote.numero) CUDE=$($creditNote.uuid_cude) estado=$($creditNote.estado_dian)" -ForegroundColor Green

# 4. Descargar documentos de nota crédito
Save-Document -InvoiceId $creditId -Kind "signed-xml" -FileName "core-credit-note-signed.xml" | Out-Null
Save-Document -InvoiceId $creditId -Kind "app-response" -FileName "core-credit-note-app-response.xml" | Out-Null
Save-Document -InvoiceId $creditId -Kind "pdf" -FileName "core-credit-note.pdf" | Out-Null

$creditXml = Get-Content -Raw -Path (Join-Path $OutDir "core-credit-note-signed.xml")
if ($creditXml -notmatch "<CreditNote" -or $creditXml -notmatch "BillingReference" -or $creditXml -notmatch "DiscrepancyResponse") {
    throw "XML firmado de nota crédito inválido."
}

[PSCustomObject]@{
    Status = "OK"
    CoreGoUrl = $BaseUrl
    InvoiceId = $invoiceId
    InvoiceNumber = $invoiceNumber
    InvoiceCufe = $invoice.uuid_cude
    CreditNoteId = $creditId
    CreditNoteNumber = "$($creditNote.prefijo)$($creditNote.numero)"
    CreditNoteCude = $creditNote.uuid_cude
    OutputDir = $OutDir
} | ConvertTo-Json -Depth 5
