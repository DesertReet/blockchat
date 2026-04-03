export interface MSDeviceCodeResponse {
	device_code: string;
	user_code: string;
	verification_uri: string;
	expires_in: number;
	interval: number;
	message: string;
}

export interface MSTokenResponse {
	access_token: string;
	token_type: string;
	expires_in: number;
	scope: string;
	refresh_token?: string;
}

export interface MSTokenError {
	error: string;
	error_description: string;
}

export interface XboxLiveAuthResponse {
	Token: string;
	DisplayClaims: { xui: Array<{ uhs: string }> };
}

export interface MinecraftAuthResponse {
	access_token: string;
	token_type: string;
	expires_in: number;
}

export interface MinecraftProfile {
	id: string;
	name: string;
	skins: Array<{ id: string; state: string; url: string; variant: string }>;
	capes: Array<{ id: string; state: string; url: string; alias: string }>;
}
