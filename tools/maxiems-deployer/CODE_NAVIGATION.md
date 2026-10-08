# Bản đồ tìm type trong MaxiEMS Deployer

Tài liệu này giúp xác định một type nằm trong source của project, .NET runtime
hay dependency NuGet. Không phải mọi class xuất hiện trong code đều có file
định nghĩa trong repository.

## 1. Cách C# resolve type

Khi gặp một type như `CancellationTokenSource`, kiểm tra theo thứ tự:

1. type trong cùng namespace;
2. namespace trong các dòng `using` đầu file;
3. namespace được SDK thêm qua `ImplicitUsings`;
4. project reference khác trong solution;
5. package/framework reference ngoài repository.

Project bật:

```xml
<ImplicitUsings>enable</ImplicitUsings>
```

trong `Directory.Build.props`. Vì vậy các namespace .NET phổ biến như
`System`, `System.IO`, `System.Linq`, `System.Threading` và
`System.Threading.Tasks` không cần xuất hiện ở đầu từng file.

## 2. Hai ví dụ thường gặp

### `CancellationTokenSource`

```text
Owner:      .NET runtime
Namespace:  System.Threading
Assembly:   System.Private.CoreLib
Repo file:  không có
```

Nó tạo và điều khiển cancellation signal. `CreateLinkedTokenSource()` tạo một
source bị cancel khi parent token bị cancel; `CancelAfter()` thêm timeout.

### `SecretRedactor`

```text
Owner:      MaxiEMS Deployer
Namespace:  Maxiems.Deployer.Core.Security
File:       src/Maxiems.Deployer.Core/Security/SecretRedactor.cs
Tests:      tests/Maxiems.Deployer.Core.Tests/Security/SecretRedactorTests.cs
```

Nó thay secret value và credential pattern bằng `[REDACTED]` trước khi dữ liệu
đi vào progress, log hoặc deployment report.

## 3. Type map cho deployment flow

| Type | Owner | Definition |
|---|---|---|
| `DeploymentEngine` | Project | `src/Maxiems.Deployer.Infrastructure/Deployment/DeploymentEngine.cs` |
| `DeploymentExecutionRequest` | Project | `src/Maxiems.Deployer.Infrastructure/Deployment/DeploymentExecution.cs` |
| `DeploymentRunBuilder` | Project | `src/Maxiems.Deployer.Infrastructure/Deployment/DeploymentRunBuilder.cs` |
| `DeploymentFailureRecovery` | Project | `src/Maxiems.Deployer.Infrastructure/Deployment/DeploymentFailureRecovery.cs` |
| `SshBootstrapSession` | Project | `src/Maxiems.Deployer.Infrastructure/Deployment/SshBootstrapSession.cs` |
| `SshReleaseTransactionClient` | Project | `src/Maxiems.Deployer.Infrastructure/Deployment/SshReleaseTransactionClient.cs` |
| `SshNetTransportSession` | Project | `src/Maxiems.Deployer.Infrastructure/Ssh/SshNetTransportSession.cs` |
| `PayloadVerifier` | Project | `src/Maxiems.Deployer.Infrastructure/Payload/PayloadVerifier.cs` |
| `OpenEmsComponentReconciler` | Project | `src/Maxiems.Deployer.Infrastructure/OpenEms/OpenEmsComponentReconciler.cs` |
| `PayloadComponentManifestResolver` | Project | `src/Maxiems.Deployer.Infrastructure/OpenEms/PayloadComponentManifestResolver.cs` |
| `BmsReadinessProbe` | Project | `src/Maxiems.Deployer.Infrastructure/Deployment/BmsReadinessProbe.cs` |
| `SecretRedactor` | Project | `src/Maxiems.Deployer.Core/Security/SecretRedactor.cs` |
| `SecretMaterial` | Project | `src/Maxiems.Deployer.Core/Security/SecretMaterial.cs` |
| `OpenEmsManagementPassword` | Project | `src/Maxiems.Deployer.Core/Security/OpenEmsManagementPassword.cs` |
| `ReleaseId` | Project | `src/Maxiems.Deployer.Core/Contracts/ReleaseId.cs` |
| `CancellationToken` | .NET | `System.Threading` |
| `CancellationTokenSource` | .NET | `System.Threading` |
| `TimeProvider` | .NET | `System` |
| `Task` / `ValueTask` | .NET | `System.Threading.Tasks` |
| `ImmutableArray` | .NET library | `System.Collections.Immutable` |
| `SshClient` / `SftpClient` | SSH.NET package | `Renci.SshNet` |
| `SqliteConnection` | Microsoft.Data.Sqlite package | `Microsoft.Data.Sqlite` |

## 4. Lệnh tìm nhanh

Tìm project-owned definition:

```bash
rg -n "^(public|internal|private|protected)?.*(class|record|interface|enum) SecretRedactor" src tests
```

Tìm tất cả nơi dùng type:

```bash
rg -n "CancellationTokenSource|SecretRedactor" src tests
```

Tìm namespace/import:

```bash
rg -n "using .*Security|using System.Threading" src tests
```

Với VS Code, Visual Studio hoặc Rider:

- `F12` / Go to Definition mở source project hoặc framework metadata;
- Shift+F12 / Find References liệt kê call sites;
- Go to Type tìm theo tên class không cần biết file;
- framework type có thể mở metadata/decompiled source vì definition không nằm
  trong repository.

## 5. Quy ước source mới

- Mỗi project-owned public boundary type phải có tên file trùng tên type khi có
  thể.
- Type chỉ dùng để gom state nội bộ có thể là nested private class gần owner.
- Không copy hoặc bọc lại .NET framework type chỉ để có definition trong repo.
- Public/security/deployment boundary type cần XML summary nói rõ ownership và
  trách nhiệm.
- Khi thêm dependency package, cập nhật type map nếu type đó xuất hiện trong
  orchestration chính.
