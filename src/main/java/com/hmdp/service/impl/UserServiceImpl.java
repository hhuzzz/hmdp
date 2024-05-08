package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.MailUtils;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.mail.MessagingException;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

//    @Override
//    public Result sendCode(String phone, HttpSession session) {
//        //校验手机号
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            //手机号不符合
//            return Result.fail("手机号格式错误");
//        }
//        //手机号符合,生成验证码
//        String code = RandomUtil.randomNumbers(6);
//        /*//保存验证码到session
//        session.setAttribute("code", code);*/
//        //保存验证码到redis
//        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        //发送验证码
//        log.debug("发送验证码成功，验证码：{}", code);
//        //返回ok
//        return Result.ok();
//    }

    @Override
    public Result sendCode(String email, HttpSession session) throws MessagingException {
        // 1. 判断是否在一级限制条件内
        Boolean oneLevelLimit = stringRedisTemplate.opsForSet().isMember(ONE_LEVERLIMIT_KEY + email, "1");
        if (oneLevelLimit != null && oneLevelLimit) {
            // 在一级限制条件内，不能发送验证码
            return Result.fail("您需要等5分钟后再请求");
        }

        // 2. 判断是否在二级限制条件内
        Boolean twoLevelLimit = stringRedisTemplate.opsForSet().isMember(TWO_LEVERLIMIT_KEY + email, "1");
        if (twoLevelLimit != null && twoLevelLimit) {
            // 在二级限制条件内，不能发送验证码
            return Result.fail("您需要等20分钟后再请求");
        }

        // 3. 检查过去1分钟内发送验证码的次数
        long oneMinuteAgo = System.currentTimeMillis() - 60 * 1000;
        long count_oneminute = stringRedisTemplate.opsForZSet().count(SENDCODE_SENDTIME_KEY + email, oneMinuteAgo, System.currentTimeMillis());
        if (count_oneminute >= 1) {
            // 过去1分钟内已经发送了1次，不能再发送验证码
            return Result.fail("距离上次发送时间不足1分钟，请1分钟后重试");
        }

        // 4. 检查发送验证码的次数
        long fiveMinutesAgo = System.currentTimeMillis() - 5 * 60 * 1000;
        long count_fiveminute = stringRedisTemplate.opsForZSet().count(SENDCODE_SENDTIME_KEY + email, fiveMinutesAgo, System.currentTimeMillis());
        if (count_fiveminute % 3 == 2 && count_fiveminute > 5) {
            // 发送了8, 11, 14, ...次，进入二级限制
            stringRedisTemplate.opsForSet().add(TWO_LEVERLIMIT_KEY + email, "1");
            stringRedisTemplate.expire(TWO_LEVERLIMIT_KEY + email, 20, TimeUnit.MINUTES);
            return Result.fail("接下来如需再发送，请等20分钟后再请求");
        } else if (count_fiveminute == 5) {
            // 过去5分钟内已经发送了5次，进入一级限制
            stringRedisTemplate.opsForSet().add(ONE_LEVERLIMIT_KEY + email, "1");
            stringRedisTemplate.expire(ONE_LEVERLIMIT_KEY + email, 5, TimeUnit.MINUTES);
            return Result.fail("5分钟内已经发送了5次，接下来如需再发送请等待5分钟后重试");
        }

        //生成验证码
        String code = MailUtils.achieveCode();

        //将生成的验证码保持到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + email, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.info("发送登录验证码：{}", code);
        //发送验证码
        MailUtils.sendtoMail(email, code);

        // 更新发送时间和次数
        stringRedisTemplate.opsForZSet().add(SENDCODE_SENDTIME_KEY + email, System.currentTimeMillis() + "", System.currentTimeMillis());

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //手机号不符合
            return Result.fail("手机号格式错误");
        }
        //从redis中获取验证码 校验验证码
        /*  Object cacheCode = session.getAttribute("code");*/
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            //不一致 报错
            return Result.fail("验证码错误");
        }
        //一致 根据手机号查询用户
        User user = baseMapper
                .selectOne(new LambdaQueryWrapper<User>()
                        .eq(User::getPhone, phone));
        //判断用户是否存在
        if (user == null) {
            //不存在 创建新用户
            user = createUserWithPhone(phone);
        }
        /*//保存用户信息到session
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));*/
        //生成token
        String token = UUID.randomUUID().toString(true);
        //userDTO转map
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>()
                , CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor(
                                (name, value) -> value.toString()
                        ));
        //保存用户信息到redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, map);
        //设置过期时间
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //获取当前登陆用户
        Long id = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyy:MM:"));
        String key = USER_SIGN_KEY + yyyyMM + id;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写了redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前登陆用户
        Long id = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyy:MM:"));
        String key = USER_SIGN_KEY + yyyyMM + id;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取截至本月今天的所有签到记录
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key
                , BitFieldSubCommands
                        .create()
                        .get(BitFieldSubCommands.BitFieldType
                                .unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        //转二进制字符串
        String binaryString = Long.toBinaryString(num);
        //计算连续签到天数
        int count = 0;
        for (int i = binaryString.length() - 1; i >= 0; i--) {
            if (binaryString.charAt(i) == '1') {
                count++;
            } else {
                break;
            }
        }
        //返回
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        //生成随机昵称
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        baseMapper.insert(user);
        return user;
    }
}
