package com.qg.smpt.web.processor;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.qg.smpt.printer.OrdersDispatcher;
import com.qg.smpt.web.model.Order;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import com.qg.smpt.share.ShareMem;
import com.qg.smpt.util.JsonUtil;
import com.qg.smpt.util.Logger;
import com.qg.smpt.web.model.Constant;
import com.qg.smpt.web.model.User;
import com.qg.smpt.web.service.UserService;

import java.util.ArrayList;
import java.util.List;

@Controller
public class LoginController {
	private static final Logger LOGGER = Logger.getLogger(LoginController.class);

	@Autowired
	private UserService userService;
	
	@RequestMapping(value="/login_app", method=RequestMethod.POST, produces="application/json;charset=utf-8" )
	@ResponseBody
	public String login(@RequestBody String data,  HttpServletRequest request) {
		User user = (User)JsonUtil.jsonToObject(data, User.class);
		
		int retcode = Constant.FALSE;
		
		// check the login infomation is correct
		if(!checkInput(user)){
			return JsonUtil.jsonToMap(new String[]{"retcode"}, new String[]{String.valueOf(retcode)});
		}
		
		// run the login method.
		// login successful - return the user
		// login fail - return null
		User loginUser = userService.login(user);
		
		// set the login status
		retcode = (loginUser != null ? Constant.TRUE : Constant.FALSE);
		
		// check the login status
		// if success, store the user
		if(retcode == Constant.TRUE) {
			synchronized(ShareMem.userIdMap) {
				ShareMem.userIdMap.put(loginUser.getId(), loginUser);
			}
			return JsonUtil.jsonToMap(new String[]{"retcode","userId"}, new Object[]{retcode, loginUser.getId().toString()});
			 
		}
		
		return JsonUtil.jsonToMap(new String[]{"retcode"}, new Object[]{retcode});
	}
	
	
	@RequestMapping(value="/login", method=RequestMethod.POST, produces="application/html;charset=utf-8" )
	public String login(String userAccount, String userPassword, HttpServletRequest request, HttpServletResponse response) {
		User user = installUser(userAccount, userPassword);
		
		// check the login infomation is correct
		if(!checkInput(user)){
			 return "redirect:/webContent/html/order_index.html";
		}
		
		// run the login method.
		// login successful - return the user
		// login fail - return null
		User loginUser = userService.login(user);
		
		// set the login status
		String status = (loginUser != null ? Constant.SUCCESS : Constant.ERROR);
		
		// check the login status
		// if success, store the user
		if(status.equals(Constant.SUCCESS)) {
			 HttpSession session = request.getSession();
			 session.setAttribute("user", loginUser);

			loginUser.getLogoB();
			LOGGER.debug("该用户的id为" + loginUser.getId().toString());
			synchronized (ShareMem.userIdMap) {
				ShareMem.userIdMap.put(loginUser.getId(), loginUser);
			}

			//给用户订单委派器，用于循环查看当前的订单存量,直到session为止
//			OrdersDispatcher ordersDispatcher = new OrdersDispatcher(loginUser.getId());
//			ordersDispatcher.threadStart();
			List<Order> orders = new ArrayList<>();
			ShareMem.userOrderBufferMap.put(loginUser.getId(), orders);
//			ShareMem.userIdOrdersDispatcher.put(loginUser.getId(),ordersDispatcher);

			Cookie cookie = new Cookie("user_id", loginUser.getId().toString());
			cookie.setPath("/");
			response.addCookie(cookie);

			 return "redirect:/html/order_index.html?userId=" + loginUser.getId();
			 
		}else{
			 return "redirect:/html/user_login.html";
		}
		
	}
	
	private User installUser(String account ,String password) {
		User user = new User();
		user.setUserAccount(account);
		user.setUserPassword(password);
		return user;
	}
	
	private boolean checkInput(User user) {
		return true;
	}

	@RequestMapping(value="/exit/{userId}", method=RequestMethod.GET, produces="application/html;charset=utf-8" )
	public String exit(@PathVariable int userId, HttpServletRequest request) {
		HttpSession session = request.getSession();
		session.invalidate();
		return JsonUtil.jsonToMap(new String[]{"state"}, new Object[]{"success"});
	}
}
