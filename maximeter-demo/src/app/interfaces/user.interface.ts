export type UserRole = 'admin' | 'operator' | 'viewer';

export interface UserPermissions {
    canViewDashboard: boolean;
    canViewMeterDetail: boolean;
    canEditMeterConfig: boolean;
    canExportData: boolean;
    canManageAlerts: boolean;
    canManageUsers: boolean;
}

export interface User {
    id: string;
    username: string;
    email?: string;
    fullName: string;
    role: UserRole;
    enabled: boolean;
    createdAt?: Date;
    lastLogin?: Date;
    permissions: UserPermissions;
}

export interface LoginCredentials {
    email: string;
    password: string;
    licenseKey: string;
}

export interface LoginResponse {
    success: boolean;
    user?: User;
    message?: string;
}

export const DEFAULT_PERMISSIONS: Record<UserRole, UserPermissions> = {
    admin: {
        canViewDashboard: true,
        canViewMeterDetail: true,
        canEditMeterConfig: true,
        canExportData: true,
        canManageAlerts: true,
        canManageUsers: true,
    },
    operator: {
        canViewDashboard: true,
        canViewMeterDetail: true,
        canEditMeterConfig: true,
        canExportData: true,
        canManageAlerts: true,
        canManageUsers: false,
    },
    viewer: {
        canViewDashboard: true,
        canViewMeterDetail: true,
        canEditMeterConfig: false,
        canExportData: false,
        canManageAlerts: false,
        canManageUsers: false,
    },
};
