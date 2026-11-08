"""Windows-owned process trees: assign a suspended child before any code runs."""
from __future__ import annotations

import ctypes
from ctypes import wintypes
import subprocess
import time


class _Limits(ctypes.Structure):
    _fields_ = [('PerProcessUserTimeLimit', ctypes.c_int64), ('PerJobUserTimeLimit', ctypes.c_int64),
                ('LimitFlags', wintypes.DWORD), ('MinimumWorkingSetSize', ctypes.c_size_t),
                ('MaximumWorkingSetSize', ctypes.c_size_t), ('ActiveProcessLimit', wintypes.DWORD),
                ('Affinity', ctypes.c_size_t), ('PriorityClass', wintypes.DWORD),
                ('SchedulingClass', wintypes.DWORD)]


class _IO(ctypes.Structure):
    _fields_ = [(name, ctypes.c_uint64) for name in
                ('ReadOperationCount', 'WriteOperationCount', 'OtherOperationCount',
                 'ReadTransferCount', 'WriteTransferCount', 'OtherTransferCount')]


class _ExtendedLimits(ctypes.Structure):
    _fields_ = [('BasicLimitInformation', _Limits), ('IoInfo', _IO),
                ('ProcessMemoryLimit', ctypes.c_size_t), ('JobMemoryLimit', ctypes.c_size_t),
                ('PeakProcessMemoryUsed', ctypes.c_size_t), ('PeakJobMemoryUsed', ctypes.c_size_t)]


class _Accounting(ctypes.Structure):
    _fields_ = [(name, ctypes.c_int64) for name in
                ('TotalUserTime', 'TotalKernelTime', 'ThisPeriodTotalUserTime', 'ThisPeriodTotalKernelTime')] + [
                (name, wintypes.DWORD) for name in
                ('TotalPageFaultCount', 'TotalProcesses', 'ActiveProcesses', 'TotalTerminatedProcesses')]


class _ThreadEntry(ctypes.Structure):
    _fields_ = [(name, wintypes.DWORD) for name in
                ('dwSize', 'cntUsage', 'th32ThreadID', 'th32OwnerProcessID')] + [
                ('tpBasePri', wintypes.LONG), ('tpDeltaPri', wintypes.LONG), ('dwFlags', wintypes.DWORD)]


class ProcessJob:
    def __init__(self):
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        for name in ('CreateJobObjectW', 'CreateToolhelp32Snapshot', 'OpenThread', 'OpenProcess'):
            getattr(self.kernel, name).restype = wintypes.HANDLE
        self.handle = wintypes.HANDLE(self.kernel.CreateJobObjectW(None, None))
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        limits = _ExtendedLimits()
        limits.BasicLimitInformation.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.kernel.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            self.close()
            raise ctypes.WinError(ctypes.get_last_error())

    def attach_and_resume(self, process):
        if not self.kernel.AssignProcessToJobObject(self.handle, wintypes.HANDLE(process._handle)):
            raise ctypes.WinError(ctypes.get_last_error())
        snapshot = self.kernel.CreateToolhelp32Snapshot(4, 0)  # TH32CS_SNAPTHREAD
        if snapshot == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        snapshot = wintypes.HANDLE(snapshot)
        try:
            entry = _ThreadEntry(); entry.dwSize = ctypes.sizeof(entry)
            found = self.kernel.Thread32First(snapshot, ctypes.byref(entry))
            while found:
                if entry.th32OwnerProcessID == process.pid:
                    thread = wintypes.HANDLE(self.kernel.OpenThread(2, False, entry.th32ThreadID))
                    if not thread:
                        raise ctypes.WinError(ctypes.get_last_error())
                    try:
                        if self.kernel.ResumeThread(thread) == -1:
                            raise ctypes.WinError(ctypes.get_last_error())
                    finally:
                        self.kernel.CloseHandle(thread)
                    return
                found = self.kernel.Thread32Next(snapshot, ctypes.byref(entry))
            raise RuntimeError('Suspended child thread was not found')
        finally:
            self.kernel.CloseHandle(snapshot)

    def terminate(self):
        if not self.handle:
            return
        # ActiveProcesses can reach zero before process handles become signalled.
        # Retain handles to this job's members and wait for actual termination.
        size = 1024
        while True:
            members = ctypes.create_string_buffer(size)
            if self.kernel.QueryInformationJobObject(self.handle, 3, members, size, None):
                break
            if ctypes.get_last_error() != 234 or size >= 1024 * 1024:
                raise ctypes.WinError(ctypes.get_last_error())
            size *= 2
        count = ctypes.c_uint32.from_buffer(members, 4).value
        handles = []
        try:
            for index in range(count):
                pid = ctypes.c_size_t.from_buffer(members, 8 + index * ctypes.sizeof(ctypes.c_size_t)).value
                handle = self.kernel.OpenProcess(0x100000, False, pid)
                if handle:
                    handles.append(wintypes.HANDLE(handle))
                elif ctypes.get_last_error() != 87:
                    raise ctypes.WinError(ctypes.get_last_error())
            if not self.kernel.TerminateJobObject(self.handle, 1):
                raise ctypes.WinError(ctypes.get_last_error())
            deadline = time.monotonic() + 5
            for handle in handles:
                if self.kernel.WaitForSingleObject(handle, max(0, int((deadline - time.monotonic()) * 1000))) != 0:
                    raise TimeoutError('Owned Windows child has not exited')
        finally:
            for handle in handles:
                self.kernel.CloseHandle(handle)
        deadline = time.monotonic() + 5
        while True:
            accounting = _Accounting()
            if not self.kernel.QueryInformationJobObject(self.handle, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None):
                raise ctypes.WinError(ctypes.get_last_error())
            if not accounting.ActiveProcesses:
                return
            if time.monotonic() >= deadline:
                raise TimeoutError('Owned Windows process tree has not exited')
            time.sleep(.02)

    def close(self):
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None


def spawn_owned(argv, **kwargs):
    job = ProcessJob()
    process = None
    try:
        process = subprocess.Popen(argv, creationflags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP | 4,
                                   **kwargs)  # CREATE_SUSPENDED
        job.attach_and_resume(process)
        process._rivo_job = job
        return process
    except BaseException:
        if process is not None:
            process.kill()
            process.wait(timeout=5)
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream:
                    stream.close()
        job.close()
        raise


def desktop_package_family(executable):
    """Bind service routing to the registered Desktop package's exact binary."""
    import json
    import os
    from pathlib import Path
    script = 'Get-AppxPackage -Name OpenAI.Codex | Select-Object InstallLocation,PackageFamilyName | ConvertTo-Json -Compress'
    result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script],
        capture_output=True, text=True, encoding='utf-8', timeout=15, creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode or not result.stdout.strip():
        return None
    records = json.loads(result.stdout)
    for record in records if isinstance(records, list) else [records]:
        root = record.get('InstallLocation')
        family = record.get('PackageFamilyName')
        if (root and family and os.path.normcase(str(Path(root) / 'app' / 'resources' / 'codex.exe'))
                == os.path.normcase(str(Path(executable).resolve()))):
            return family
    return None
