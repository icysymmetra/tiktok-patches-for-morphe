<#
.SYNOPSIS
    Exclusive, fail-closed leases for the shared Windows device pool.
.DESCRIPTION
    Dot-source this file. Acquire only when a serial is needed, pass the returned context to
    every device command, renew before expiry, then release in finally. The shared directory
    must be supplied explicitly. Existing files, including expired leases, are never reclaimed.
    A lease lasts at most 30 minutes, the machine pool's limit; renew it to keep going.

    The lease keeps a noninheritable read/write Windows handle open that shares reading alone,
    so no other process can write, delete or rename the file while it is held. Renewal writes
    through that handle. The handle has no delete access, so readers that share writing
    (Get-Content, Python, Node) can inspect who holds the serial; [IO.File]::ReadAllText shares
    reading alone and still reports a sharing violation. Release closes the handle, reopens the
    path for deletion sharing reading alone, and deletes the file only when it still carries
    this context's token, so a lease another owner took in between is never removed.
    A crash leaves the lease file for recovery after the previous test is confirmed stopped.
    Do not dispose the context's stream. A context is for sequential commands in its owning
    process, not concurrent runspace use.
#>

function Initialize-DeviceLeaseFile {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'Device leases require Windows file-handle locking. No device command was sent.'
    }
    if ('Hushfeed.DeviceLeaseFile' -as [type]) { return }
    Add-Type -ErrorAction Stop -TypeDefinition @'
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
namespace Hushfeed {
    public static class DeviceLeaseFile {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafeFileHandle CreateFileW(string path, uint access, uint share,
            IntPtr security, uint disposition, uint attributes, IntPtr template);
        [StructLayout(LayoutKind.Sequential)]
        private struct Disposition { public byte DeleteFile; }
        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool SetFileInformationByHandle(SafeFileHandle handle,
            int infoClass, ref Disposition info, uint size);
        private static FileStream Open(string path, uint access, uint disposition, FileAccess mode) {
            var handle = CreateFileW(path, access, 1, IntPtr.Zero, disposition, 0x80, IntPtr.Zero);
            if (handle.IsInvalid) {
                int error = Marshal.GetLastWin32Error();
                handle.Dispose();
                if (error == 2 && disposition == 3) return null;
                throw new Win32Exception(error);
            }
            try { return new FileStream(handle, mode); }
            catch { handle.Dispose(); throw; }
        }
        // GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ, CREATE_NEW. No DELETE access, so a
        // reader that shares writing but not deleting can still open the file.
        public static FileStream Create(string path) {
            return Open(path, 0xC0000000, 1, FileAccess.ReadWrite);
        }
        // GENERIC_READ | DELETE, FILE_SHARE_READ, OPEN_EXISTING. Null when the file is gone.
        // Throws Win32Exception 32 while a reader that doesn't share deleting has it open.
        public static FileStream OpenForDeletion(string path) {
            return Open(path, 0x80010000, 3, FileAccess.Read);
        }
        public static void MarkForDeletion(FileStream stream) {
            var info = new Disposition { DeleteFile = 1 };
            if (!SetFileInformationByHandle(stream.SafeFileHandle, 4, ref info, 1))
                throw new Win32Exception(Marshal.GetLastWin32Error());
        }
    }
}
'@
}

function Get-DeviceLeasePath {
    param([string]$LeaseDir, [string]$Serial)
    if ([string]::IsNullOrWhiteSpace($LeaseDir)) { throw 'Supply the shared device lease directory explicitly.' }
    if ($Serial -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9._-]{0,127}\z' -or $Serial.EndsWith('.') -or
        $Serial -match '\A(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|\z)') {
        throw 'The exact device serial must be a safe Windows filename without path separators or a port colon.'
    }
    $directory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($LeaseDir)
    return [IO.Path]::GetFullPath((Join-Path $directory "$Serial.json"))
}

function ConvertTo-DeviceLeaseExpiry {
    param($Value)
    if ($Value -is [DateTimeOffset]) { return $Value.ToUniversalTime() }
    if ($Value -is [DateTime]) {
        if ($Value.Kind -eq [DateTimeKind]::Unspecified) { throw 'Device lease expiry has no time zone.' }
        return ([DateTimeOffset]$Value).ToUniversalTime()
    }
    # PS7 can deserialize ISO JSON dates to DateTime; PS5 leaves these as strings.
    # Never stringify a DateTime before parsing: that drops its UTC/local Kind.
    if ($Value -isnot [string] -or $Value -cnotmatch '\A\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})\z') {
        throw 'Device lease expiry must include an explicit ISO time zone.'
    }
    return [DateTimeOffset]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture).ToUniversalTime()
}

function Read-DeviceLeaseText {
    param([IO.FileStream]$Stream)
    $length = $Stream.Length
    if ($length -lt 1 -or $length -gt 16384) { throw 'Device lease metadata has an invalid size.' }
    $bytes = New-Object byte[] ([int]$length)
    $Stream.Position = 0
    $read = 0
    while ($read -lt $bytes.Length) {
        $count = $Stream.Read($bytes, $read, $bytes.Length - $read)
        if ($count -eq 0) { throw 'Device lease metadata could not be read completely.' }
        $read += $count
    }
    $encoding = New-Object Text.UTF8Encoding($false, $true)
    return $encoding.GetString($bytes)
}

function Read-DeviceLeaseRecord {
    param($Lease)
    $ErrorActionPreference = 'Stop'
    if ($null -eq $Lease -or $null -eq $Lease.Stream -or -not $Lease.Stream.CanRead -or
        -not $Lease.Stream.CanWrite -or $Lease.Stream.SafeFileHandle.IsClosed) {
        throw 'Device lease is no longer held. No device command was sent.'
    }
    return (Read-DeviceLeaseText $Lease.Stream | ConvertFrom-Json -ErrorAction Stop)
}

function Write-DeviceLeaseRecord {
    param($Lease, $Record)
    $ErrorActionPreference = 'Stop'
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Record | ConvertTo-Json -Depth 4 -Compress))
    if ($bytes.Length -gt 16384) { throw 'Device lease metadata is too large.' }
    $Lease.Stream.Position = 0
    $Lease.Stream.Write($bytes, 0, $bytes.Length)
    $Lease.Stream.SetLength($bytes.Length)
    $Lease.Stream.Flush($true)
}

function Remove-DeviceLeaseFile {
    # Runs after the lease handle is closed. The deletion handle shares reading alone, so the
    # token check and the delete see the same file. -Partial also removes a file with no
    # readable token: a first write that failed partway, in a file only this process created.
    param([string]$Path, [string]$OwnershipToken, [switch]$Partial)
    $ErrorActionPreference = 'Stop'
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $stream = $null
    while ($null -eq $stream) {
        try {
            $stream = [Hushfeed.DeviceLeaseFile]::OpenForDeletion($Path)
            if ($null -eq $stream) { return }
        } catch {
            $failure = $_.Exception
            while ($null -ne $failure.InnerException) { $failure = $failure.InnerException }
            # 32 is a sharing violation: a reader has the file open for a moment.
            if (-not ($failure -is [ComponentModel.Win32Exception] -and $failure.NativeErrorCode -eq 32) -or
                $timer.Elapsed.TotalSeconds -ge 5) {
                throw "The released device lease at '$Path' could not be deleted. $($failure.Message)"
            }
            Start-Sleep -Milliseconds 25
        }
    }
    try {
        $token = $null
        # Unreadable metadata has no token: never this context's finished record.
        try { $token = (Read-DeviceLeaseText $stream | ConvertFrom-Json -ErrorAction Stop).ownershipToken }
        catch { $token = $null }
        if ($token -ceq $OwnershipToken -or ($Partial -and $null -eq $token)) {
            [Hushfeed.DeviceLeaseFile]::MarkForDeletion($stream)
        }
    } finally { $stream.Dispose() }
}

function New-DeviceLease {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$LeaseDir,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Serial,
        [Parameter(Mandatory = $true)][ValidateLength(1, 128)][string]$Project,
        [Parameter(Mandatory = $true)][ValidateLength(1, 256)][string]$ChatIdentity,
        [Parameter(Mandatory = $true)][ValidateLength(1, 512)][string]$Purpose,
        [ValidateRange(1, 30)][int]$LeaseMinutes = 30
    )
    $ErrorActionPreference = 'Stop'
    foreach ($value in $Project, $ChatIdentity, $Purpose) {
        if ([string]::IsNullOrWhiteSpace($value)) { throw 'Device lease project, chat identity and purpose must be nonblank.' }
    }
    $path = Get-DeviceLeasePath -LeaseDir $LeaseDir -Serial $Serial
    Initialize-DeviceLeaseFile
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    try { $stream = [Hushfeed.DeviceLeaseFile]::Create($path) }
    catch {
        throw "Could not acquire the device lease for '$Serial'. Existing leases require external confirmation that their test stopped before recovery. $($_.Exception.Message)"
    }
    $lease = [pscustomobject]@{ Path = $path; Serial = $Serial; OwnershipToken = [guid]::NewGuid().ToString('N'); Stream = $stream }
    try {
        $now = [DateTime]::UtcNow
        Write-DeviceLeaseRecord -Lease $lease -Record ([ordered]@{
            schemaVersion = 1; serial = $Serial; project = $Project; chatIdentity = $ChatIdentity
            purpose = $Purpose; ownershipToken = $lease.OwnershipToken
            acquiredUtc = $now.ToString('o'); expiresUtc = $now.AddMinutes($LeaseMinutes).ToString('o')
        })
        return $lease
    } catch {
        # CREATE_NEW succeeded, so the file is ours unless another owner replaced it after close.
        $firstWrite = $_
        $stream.Dispose()
        try { Remove-DeviceLeaseFile -Path $path -OwnershipToken $lease.OwnershipToken -Partial }
        catch { throw "The device lease's first write failed ($($firstWrite.Exception.Message)), and its partial file stays. $($_.Exception.Message)" }
        throw $firstWrite
    }
}

function Assert-DeviceLease {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][AllowNull()]$Lease, [string]$Serial)
    $ErrorActionPreference = 'Stop'
    $record = Read-DeviceLeaseRecord -Lease $Lease
    if ($record.schemaVersion -ne 1 -or $record.serial -cne $Lease.Serial -or
        [string]::IsNullOrEmpty($Lease.OwnershipToken) -or $record.ownershipToken -cne $Lease.OwnershipToken -or
        ($PSBoundParameters.ContainsKey('Serial') -and $Serial -cne $Lease.Serial)) {
        throw 'Device lease ownership or serial changed. No device command was sent.'
    }
    if ((ConvertTo-DeviceLeaseExpiry $record.expiresUtc) -le [DateTimeOffset]::UtcNow) {
        throw 'Device lease expired. No device command was sent; recovery requires confirmation that the test stopped.'
    }
}

function Test-DeviceLease {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][AllowNull()]$Lease)
    try { Assert-DeviceLease -Lease $Lease; return $true } catch { return $false }
}

function Update-DeviceLease {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$Lease, [ValidateRange(1, 30)][int]$LeaseMinutes = 30)
    $ErrorActionPreference = 'Stop'
    Assert-DeviceLease -Lease $Lease
    $record = Read-DeviceLeaseRecord -Lease $Lease
    $record.expiresUtc = [DateTime]::UtcNow.AddMinutes($LeaseMinutes).ToString('o')
    Write-DeviceLeaseRecord -Lease $Lease -Record $record
}

function Invoke-WithDeviceLease {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowNull()]$Lease,
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [string]$Serial
    )
    $check = @{ Lease = $Lease }
    if ($PSBoundParameters.ContainsKey('Serial')) { $check.Serial = $Serial }
    Assert-DeviceLease @check
    & $Action
}

function Remove-DeviceLease {
    # -WarnOnFailure is for finally blocks: a release that fails there warns instead of
    # replacing the error that ended the run. The file then stays until confirmed recovery.
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][AllowNull()]$Lease, [switch]$WarnOnFailure)
    $ErrorActionPreference = 'Stop'
    if ($null -eq $Lease -or $null -eq $Lease.Stream) { return }
    try {
        try { Assert-DeviceLease -Lease $Lease }
        finally {
            $Lease.Stream.Dispose()
            $Lease.Stream = $null
        }
        Remove-DeviceLeaseFile -Path $Lease.Path -OwnershipToken $Lease.OwnershipToken
    } catch {
        if (-not $WarnOnFailure) { throw }
        Write-Warning "The device lease for '$($Lease.Serial)' was not released and stays until someone confirms the test stopped. $($_.Exception.Message)"
    }
}
