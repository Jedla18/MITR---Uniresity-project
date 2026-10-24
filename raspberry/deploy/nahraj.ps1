<#
.SYNOPSIS
Nahraje program domku na Raspberry a restartuje sluzbu, pokud uz bezi.

.DESCRIPTION
Spousti se na PC z korene projektu:  .\deploy\nahraj.ps1 -Cil uzivatel@raspberrypi.local
Nejdriv na Raspberry smaze starou slozku ~/domek/domek (slozka ~/domek/venv zustane), potom do
~/domek nahraje slozky domek a deploy a soubor requirements-pi.txt.
Kdyz PowerShell odmitne skript spustit, povol ho jen pro toto okno:
  Set-ExecutionPolicy -Scope Process Bypass

Soubor je zamerne jen v ASCII: Windows PowerShell 5.1 cte soubory bez BOM jako ANSI,
takze by z ceske diakritiky vznikly jine znaky.

.PARAMETER Cil
Ucet a adresa Raspberry, napriklad uzivatel@raspberrypi.local.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Cil
)

$ErrorActionPreference = "Stop"

# Kdyz nativni prikaz (ssh, scp) selze, skript skonci se stejnym navratovym kodem.
function Zkontroluj-Prikaz([string]$Popis) {
    if ($LASTEXITCODE -ne 0) {
        Write-Host "CHYBA: $Popis selhal (navratovy kod $LASTEXITCODE)." -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

# Koren projektu je slozka nad deploy, takze skript jde spustit odkudkoli.
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    ssh $Cil "mkdir -p ~/domek"
    Zkontroluj-Prikaz "ssh mkdir"

    # Stara slozka domek na Raspberry se pred nahranim smaze. Sluzba bezi jako root (a zkousky pres
    # sudo take), takze muze v ~/domek/domek vytvorit slozky __pycache__ patrici rootovi. Do nich
    # by scp jako bezny uzivatel nemohl zapsat a nahrani by skoncilo chybou Permission denied.
    # Smazani navic odstrani moduly, ktere uz na PC nejsou. Slozka ~/domek/venv se nemaze.
    ssh $Cil "sudo rm -rf ~/domek/domek"
    Zkontroluj-Prikaz "ssh rm"

    scp -r domek deploy requirements-pi.txt "${Cil}:~/domek/"
    Zkontroluj-Prikaz "scp"

    # try-restart restartuje sluzbu jen tehdy, kdyz bezi. Pri prvnim nahrani jeste neexistuje,
    # proto || true: to neni chyba.
    ssh $Cil "sudo systemctl try-restart domek || true"
    Zkontroluj-Prikaz "ssh systemctl try-restart"
}
finally {
    Pop-Location
}

Write-Host "Hotovo: program je nahrany do ${Cil}:~/domek"
