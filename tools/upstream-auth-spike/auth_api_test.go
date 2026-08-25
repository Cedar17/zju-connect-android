package upstreamauthspike

import (
	"testing"

	"github.com/mythologyli/zju-connect/client/atrust/auth"
	"github.com/mythologyli/zju-connect/client/authchallenge"
)

func TestAndroidAuthAPIShape(t *testing.T) {
	handler := authchallenge.HandlerFuncs{
		Code: func(challenge authchallenge.CodeChallenge) (authchallenge.CodeResponse, error) {
			return authchallenge.CodeResponse{Code: "123456"}, nil
		},
		TextCaptcha: func(challenge authchallenge.TextCaptchaChallenge) (authchallenge.TextCaptchaResponse, error) {
			return authchallenge.TextCaptchaResponse{Code: "abcd"}, nil
		},
		ClickCaptcha: func(challenge authchallenge.ClickCaptchaChallenge) (authchallenge.ClickCaptchaResponse, error) {
			return authchallenge.ClickCaptchaResponse{
				Points: []authchallenge.Point{{X: 10, Y: 20}},
			}, nil
		},
		ExternalLogin: func(challenge authchallenge.ExternalLoginChallenge) (authchallenge.ExternalLoginResponse, error) {
			return authchallenge.ExternalLoginResponse{CallbackURL: "https://vpn.zju.edu.cn/callback"}, nil
		},
	}

	_ = auth.LoginOptions{
		DeviceID:         "0123456789abcdef0123456789abcdef",
		Cookies:          []auth.Cookie{{Host: "vpn.zju.edu.cn:443", Scheme: "https", Name: "sid", Value: "sid"}},
		ChallengeHandler: handler,
	}

	method, err := auth.NewLoginMethod(auth.LoginMethodOptions{
		AuthType: "auth/psw",
		Username: "student",
		Password: "password",
		Domain:   "default",
	})
	if err != nil {
		t.Fatalf("NewLoginMethod: %v", err)
	}
	if method == nil {
		t.Fatal("NewLoginMethod returned nil")
	}

	session := auth.NewSession("vpn.zju.edu.cn:443", nil)
	_ = session.GetAuthInfoList
	_ = session.Login
	_ = session.ClientResource
}
