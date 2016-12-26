package com.lulan.shincolle.entity.battleship;

import java.util.List;

import com.lulan.shincolle.ai.EntityAIShipRangeAttack;
import com.lulan.shincolle.entity.BasicEntityShipHostile;
import com.lulan.shincolle.handler.ConfigHandler;
import com.lulan.shincolle.init.ModItems;
import com.lulan.shincolle.init.ModSounds;
import com.lulan.shincolle.network.S2CSpawnParticle;
import com.lulan.shincolle.proxy.CommonProxy;
import com.lulan.shincolle.reference.ID;
import com.lulan.shincolle.utility.CalcHelper;
import com.lulan.shincolle.utility.ParticleHelper;
import com.lulan.shincolle.utility.TargetHelper;
import com.lulan.shincolle.utility.TeamHelper;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;

public class EntityBattleshipNGTBoss extends BasicEntityShipHostile
{
	
	private final BossInfoServer bossInfo = new BossInfoServer(this.getDisplayName(), BossInfo.Color.YELLOW, BossInfo.Overlay.PROGRESS);
	

	public EntityBattleshipNGTBoss(World world)
	{
		super(world);
		this.setSize(1.7F, 7F);
		this.setStateMinor(ID.M.ShipClass, ID.Ship.BattleshipNagato);
		this.dropItem = new ItemStack(ModItems.ShipSpawnEgg, 1, getStateMinor(ID.M.ShipClass)+2);
		
        //basic attr
		this.atk = (float) ConfigHandler.scaleBossLarge[ID.ATK];
        this.atkSpeed = (float) ConfigHandler.scaleBossLarge[ID.SPD];
        this.atkRange = (float) ConfigHandler.scaleBossLarge[ID.HIT];
        this.defValue = (float) ConfigHandler.scaleBossLarge[ID.DEF];
        this.movSpeed = (float) ConfigHandler.scaleBossLarge[ID.MOV];

        //AI flag
        this.startEmotion = 0;
        this.startEmotion2 = 0;
        this.headTilt = false;
 
	    //設定基本屬性
	    getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(ConfigHandler.scaleBossLarge[ID.HP]);
		getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(this.movSpeed);
		getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(64); //此為找目標, 路徑的範圍
		getEntityAttribute(SharedMonsterAttributes.KNOCKBACK_RESISTANCE).setBaseValue(1D);
		if (this.getHealth() < this.getMaxHealth()) this.setHealth(this.getMaxHealth());
				
		//設定AI
		this.setAIList();
		this.setAITargetList();
		
		//model display
		this.setStateEmotion(ID.S.State, ID.State.EQUIP02, false);
	}
	
	@Override
	protected boolean canDespawn()
	{
		if (ConfigHandler.despawnBoss > -1)
		{
			return this.ticksExisted > ConfigHandler.despawnBoss;
		}
        
		return false;
    }
	
	@Override
	public float getEyeHeight()
	{
		return this.height * 0.5F;
	}
	
	//setup AI
	@Override
	protected void setAIList()
	{
		super.setAIList();

		//use range attack
		this.tasks.addTask(1, new EntityAIShipRangeAttack(this));
	}
	
	//num rensouhou++
	@Override
  	public void onLivingUpdate()
	{
  		super.onLivingUpdate();

  		//client side
  		if (this.world.isRemote)
  		{
  			if (this.ticksExisted % 4 == 0)
  			{
  				//生成裝備冒煙特效
  				if (getStateEmotion(ID.S.State) >= ID.State.EQUIP00)
  				{
  					//計算煙霧位置
  	  				float[] partPos = CalcHelper.rotateXZByAxis(-1.8F, 0F, (this.renderYawOffset % 360) / 57.2957F, 1F);	
  	  				ParticleHelper.spawnAttackParticleAt(posX+partPos[1], posY+5.5D, posZ+partPos[0], 0D, 0D, 0D, (byte)24);
  				}
  				
  				if (this.ticksExisted % 8 == 0)
  	  			{
  					//生成氣彈特效
  	  				if (getStateEmotion(ID.S.Phase) == 1 || getStateEmotion(ID.S.Phase) == 3)
  	  				{
  	  	  				ParticleHelper.spawnAttackParticleAtEntity(this, 0.3D, 2D, 0D, (byte)1);
  	  				}
  	  			}//end 8 ticks
  			}//end 4 ticks
  		}//end client side
  	}
  	
  	//TYPE 91 AP FIST
  	@Override
  	public boolean attackEntityWithHeavyAmmo(Entity target)
  	{	
  		//get attack value
		float atk1 = CalcHelper.calcDamageBySpecialEffect(this, target, this.atk * 3F, 2);
		float atk2 = this.atk * 3F; //AE dmg without modifier
		
		boolean isTargetHurt = false;

		//計算目標距離
		float tarX = (float)target.posX;	//for miss chance calc
		float tarY = (float)target.posY;
		float tarZ = (float)target.posZ;
		float distX = tarX - (float)this.posX;
		float distY = tarY - (float)this.posY;
		float distZ = tarZ - (float)this.posZ;
        float distSqrt = MathHelper.sqrt(distX*distX + distY*distY + distZ*distZ);
        float dX = distX / distSqrt;
        float dY = distY / distSqrt;
        float dZ = distZ / distSqrt;
	
		//play cannon fire sound at attacker
		int atkPhase = getStateEmotion(ID.S.Phase);
		
        switch (atkPhase)
        {
        case 1:
        	this.playSound(ModSounds.SHIP_AP_P2, ConfigHandler.volumeFire, 1F);
        break;
        case 3:
        	this.playSound(ModSounds.SHIP_AP_ATTACK, ConfigHandler.volumeFire, 1F);
        break;
    	default:
    		this.playSound(ModSounds.SHIP_AP_P1, ConfigHandler.volumeFire, 1F);
    	break;
        }
		
        //play entity attack sound
        if (this.getRNG().nextInt(10) > 7)
        {
        	this.playSound(ModSounds.SHIP_HIT, this.getSoundVolume(), this.getSoundPitch());
        }
        
        //phase++
        TargetPoint point = new TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64D);
        atkPhase++;
      
        if (atkPhase > 3)
        {	//攻擊準備完成, 計算攻擊傷害
        	//display hit particle on target
	        CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 21, posX, posY, posZ, target.posX, target.posY, target.posZ, true), point);
        	
        	//calc miss chance, miss: atk1 = 0, atk2 = 50%
            if (this.rand.nextFloat() < this.getEffectEquip(ID.EF_MISS))
            {	//MISS
            	atk1 *= 0.2F;
            	atk2 *= 0.1F;
            	//spawn miss particle
            	CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 10, false), point);
            }
            else if (this.rand.nextFloat() < 0.15F)
            {	//CRI
        		atk1 *= 1.5F;
        		atk2 *= 1.5F;
        		//spawn critical particle
        		CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 11, false), point);
            }
            
            //vs player = 25% dmg
      		if (target instanceof EntityPlayer)
      		{
      			atk1 *= 0.25F;
      			atk2 *= 0.25F;
      			
        		if (atk1 > 150F)
        		{
        			atk1 = 150F;	//charge creeper
        			atk2 = 50F;		//TNT
        		}
      		}
      		
      		//對本體造成atk1傷害
      		isTargetHurt = target.attackEntityFrom(DamageSource.causeMobDamage(this), atk1);
      		
      		this.motionX = 0D;
  			this.motionY = 0D;
  			this.motionZ = 0D;
  			this.posX = tarX+dX*2F;
  			this.posY = tarY;
  			this.posZ = tarZ+dZ*2F;
  			this.setPosition(posX, posY, posZ);
      		
      		//對範圍造成atk2傷害
            EntityLivingBase hitEntity = null;
            AxisAlignedBB impactBox = this.getEntityBoundingBox().expand(5D, 4D, 5D); 
            List hitList = this.world.getEntitiesWithinAABB(EntityLivingBase.class, impactBox);
            float atkTemp = atk2;
            
            //搜尋list, 找出第一個可以判定的目標, 即傳給onImpact
            if (hitList != null && !hitList.isEmpty())
            {
                for (int i = 0; i < hitList.size(); ++i)
                {
                	atkTemp = atk2;
                	hitEntity = (EntityLivingBase) hitList.get(i);
                	
                	//目標不能是自己 or 主人
                	if (hitEntity != this && !TargetHelper.checkUnattackTargetList(hitEntity) && hitEntity.canBeCollidedWith())
                	{
                		//calc miss and cri
                		if (this.rand.nextFloat() < this.getEffectEquip(ID.EF_MISS))
                		{	//MISS
                        	atkTemp *= 0.5F;
                        }
                        else if (this.rand.nextFloat() < this.getEffectEquip(ID.EF_CRI))
                        {	//CRI
                    		atkTemp *= 1.5F;
                        }
                		
                		//若攻擊到同陣營entity (ex: owner), 則傷害設為0 (但是依然觸發擊飛特效)
                		if (TeamHelper.checkSameOwner(this, hitEntity))
                		{
                			atkTemp = 0F;
                    	}
                		
                		//若攻擊到玩家, 限制最大傷害
                    	if (hitEntity instanceof EntityPlayer)
                    	{
                    		atkTemp *= 0.25F;
                    		
                    		if (atkTemp > 50F)
                    		{
                    			atkTemp = 50F;
                    		}
                    	}

                		//attack
                	    hitEntity.attackEntityFrom(DamageSource.causeMobDamage(this), atkTemp);
                	}//end can be collided with
                }//end hit target list for loop
            }//end hit target list != null
        	
        	this.setStateEmotion(ID.S.Phase, 0, true);
        }
        else {
        	if(atkPhase == 2) {
        		CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 23, this.posX, this.posY, this.posZ, 3D, 0.3D, 0D, true), point);
        	}
        	else {
        		CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 22, this.posX, this.posY, this.posZ, 3D, 3D, 0D, true), point);
        	}
    		
        	this.setStateEmotion(ID.S.Phase, atkPhase, true);
        }
        
        //show emotes
		applyEmotesReaction(3);
        
        return isTargetHurt;
	}
  	
	@Override
	public void applyParticleAtAttacker(int type, Entity target, float[] vec)
	{
  		TargetPoint point = new TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64D);
        
  		switch (type)
  		{
  		case 1:  //light cannon
  			CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 19, this.posX, this.posY+3.5D, this.posZ, vec[0], 2.8F, vec[2], true), point);
  		break;
		default: //melee
			CommonProxy.channelP.sendToAllAround(new S2CSpawnParticle(this, 0, true), point);
		break;
  		}
  	}

	@Override
	public int getDamageType()
	{
		return ID.ShipDmgType.BATTLESHIP;
	}
	
  	/** for boss hp bar display */
  	@Override
    public boolean isNonBoss()
    {
        return false;
    }
  	
  	@Override
    public void addTrackingPlayer(EntityPlayerMP player)
    {
        super.addTrackingPlayer(player);
        this.bossInfo.addPlayer(player);
    }

  	@Override
    public void removeTrackingPlayer(EntityPlayerMP player)
    {
        super.removeTrackingPlayer(player);
        this.bossInfo.removePlayer(player);
    }
  	
  	@Override
    protected void updateAITasks()
    {
    	this.bossInfo.setPercent(this.getHealth() / this.getMaxHealth());
    }
	

}

